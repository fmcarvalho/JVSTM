/*
 * JVSTM: a Java library for Software Transactional Memory
 * Copyright (C) 2005 INESC-ID Software Engineering Group
 * http://www.esw.inesc-id.pt
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 * Author's contact:
 * INESC-ID Software Engineering Group
 * Rua Alves Redol 9
 * 1000 - 029 Lisboa
 * Portugal
 */
package jvstm;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import jvstm.util.Cons;

/**
 * Parallel Nested Transaction used to represent a part of a transaction that is
 * running (potentially) in parallel with other subparts of the same
 * transaction. The programmer is responsible for identifying the parts of a
 * transaction that he wants to run concurrently. Consequently, those parts may
 * not run in program order. The only guarantee is that their execution will be
 * equivalent to some sequential order (plus the properties of opacity). If that
 * guarantee is already provided by the disjoint accesses of each subpart,
 * consider using UnsafeParallelTransaction.
 * 
 * @author nmld
 * 
 */
public class ParallelNestedTransaction extends ReadWriteTransaction {

    protected static final ExecuteParallelNestedTxSequentiallyException EXECUTE_SEQUENTIALLY_EXCEPTION = new ExecuteParallelNestedTxSequentiallyException();

    protected ThreadLocal<AtomicInteger> blocksFree = new ThreadLocal<AtomicInteger>() {
	@Override
	protected AtomicInteger initialValue() {
	    return new AtomicInteger(0);
	}
    };

    protected ThreadLocal<Cons<ReadBlock>> blocksPool = new ThreadLocal<Cons<ReadBlock>>() {
	@Override
	protected Cons<ReadBlock> initialValue() {
	    return Cons.empty();
	}
    };

    protected Cons<ReadBlock> globalReads;
    protected Map<VBox, InplaceWrite> nestedReads;

    public ParallelNestedTransaction(ReadWriteTransaction parent) {
	super(parent);

	int[] parentVers = parent.ancVersions;
	super.ancVersions = new int[parentVers.length + 1];
	super.ancVersions[0] = parent.nestedVersion;
	for (int i = 0; i < parentVers.length; i++) {
	    this.ancVersions[i + 1] = parentVers[i];
	}

	this.nestedReads = new HashMap<VBox, InplaceWrite>();
	this.globalReads = Cons.empty();
	this.boxesWritten = parent.boxesWritten;
    }

    public ParallelNestedTransaction(ReadWriteTransaction parent, boolean multithreaded) {
	super(parent);
	super.ancVersions = EMPTY_VERSIONS;
	this.nestedReads = ReadWriteTransaction.EMPTY_MAP;
	this.globalReads = Cons.empty();
    }

    @Override
    public Transaction makeUnsafeMultithreaded() {
	throw new Error("An Unsafe Parallel Transaction may only be spawned by another Unsafe or a Top-Level transaction");
    }

    @Override
    public Transaction makeNestedTransaction(boolean readOnly) {
	throw new Error(
		"A Parallel Nested Transaction cannot spawn a Linear Nested Transaction yet. Consider using a single Parallel Nested Transaction instead.");
    }

    @Override
    protected Transaction commitAndBeginTx(boolean readOnly) {
	commitTx(true);
	return beginWithActiveRecord(readOnly, null);
    }

    // Returns -2 if self; -1 if not anc; >= 0 as version on anc otherwise
    protected int retrieveAncestorVersion(Transaction tx) {
	if (tx == this)
	    return -2;

	int i = 0;
	Transaction nextParent = parent;
	while (nextParent != null) {
	    if (nextParent == tx) {
		return ancVersions[i];
	    }
	    nextParent = nextParent.parent;
	    i++;
	}
	return -1;
    }

    private Transaction retrieveLowestCommonAncestor(Transaction tx) {
	Transaction current = tx;
	while (current != null) {
	    if (retrieveAncestorVersion(current) >= 0) {
		return current;
	    }
	    current = current.parent;
	}
	return null;
    }

    @Override
    protected void abortTx() {
	if (this.orec.version != OwnershipRecord.ABORTED) {
	    manualAbort();
	}
	Transaction.current.set(parent);
    }

    private void manualAbort() {
	for (ParallelNestedTransaction mergedIntoParent : getRWParent().mergedTxs) {
	    for (VBox vboxMergedIntoParent : mergedIntoParent.boxesWrittenInPlace) {
		InplaceWrite inplaceWrite = vboxMergedIntoParent.inplace;
		if (inplaceWrite.orec.owner == this && inplaceWrite.next != null) {
		    InplaceWrite newWriteAtHead = inplaceWrite.next;
		    while (newWriteAtHead.next != null && newWriteAtHead.next.orec.owner == this) {
			newWriteAtHead = newWriteAtHead.next;
		    }
		    vboxMergedIntoParent.inplace = newWriteAtHead;
		}
	    }
	}
	for (VBox vboxMergedIntoParent : getRWParent().boxesWrittenInPlace) {
	    InplaceWrite inplaceWrite = vboxMergedIntoParent.inplace;
	    if (inplaceWrite.orec.owner == this && inplaceWrite.next != null) {
		InplaceWrite newWriteAtHead = inplaceWrite.next;
		while (newWriteAtHead.next != null && newWriteAtHead.next.orec.owner == this) {
		    newWriteAtHead = newWriteAtHead.next;
		}
		vboxMergedIntoParent.inplace = newWriteAtHead;
	    }
	}

	this.orec.version = OwnershipRecord.ABORTED;
	for (ReadWriteTransaction child : mergedTxs) {
	    child.orec.version = OwnershipRecord.ABORTED;
	}
	super.boxesWritten = null;

	int i = 0;
	for (ReadBlock block : globalReads) {
	    block.free = true;
	    i++;
	}
	blocksFree.get().addAndGet(i);

	this.globalReads = null;
	this.nestedReads = null;
	super.mergedTxs = null;
    }

    protected <T> T readGlobal(VBox<T> vbox) {
	VBoxBody<T> body = vbox.body;
	if (body.version > number) {
	    throw EARLYABORT_EXCEPTION;
	}

	ReadBlock readBlock = null;
	if (next < 0) {
	    if (blocksFree.get().get() > 0) {
		for (ReadBlock poolBlock : blocksPool.get()) {
		    if (poolBlock.free) {
			poolBlock.free = false;
			readBlock = poolBlock;
			blocksFree.get().decrementAndGet();
			break;
		    }
		}
	    } else {
		readBlock = new ReadBlock(blocksFree.get());
	    }
	    next = 999;
	    globalReads = globalReads.cons(readBlock);
	} else {
	    readBlock = globalReads.first();
	}
	readBlock.entries[next--] = vbox;
	return body.value;
    }

    @Override
    public <T> T getBoxValue(VBox<T> vbox) {
	InplaceWrite<T> inplaceWrite = vbox.inplace;
	T value = inplaceWrite.tempValue;
	OwnershipRecord inplaceOrec = inplaceWrite.orec;

	if (inplaceOrec.version > 0 && inplaceOrec.version <= number) {
	    value = readGlobal(vbox);
	    return value;
	}

	do {
	    int entryNestedVersion = inplaceOrec.nestedVersion;
	    int versionOnAnc = retrieveAncestorVersion(inplaceOrec.owner);
	    if (versionOnAnc >= 0) {
		if (entryNestedVersion > versionOnAnc) {
		    // eager w-r conflict, may restart immediately
		    manualAbort();
		    throw new CommitException(inplaceOrec.owner);
		}
		nestedReads.put(vbox, inplaceWrite);
		return (value == NULL_VALUE) ? null : value;
	    }
	    if (versionOnAnc == -2) {
		return (value == NULL_VALUE) ? null : value;
	    }
	    inplaceWrite = inplaceWrite.next;
	    if (inplaceWrite == null) {
		break;
	    }
	    value = inplaceWrite.tempValue;
	    inplaceOrec = inplaceWrite.orec;
	} while (true);

	if (boxesWritten != EMPTY_MAP) {
	    value = (T) boxesWritten.get(vbox);
	    if (value != null) {
		return (value == NULL_VALUE) ? null : value;
	    }
	}

	value = readGlobal(vbox);
	return value;

    }

    protected static final ThreadLocal<Integer> backoffTime = new ThreadLocal<Integer>() {
	@Override
	protected Integer initialValue() {
	    return 1;
	}
    };

    @Override
    public <T> void setBoxValue(jvstm.VBox<T> vbox, T value) {
	InplaceWrite<T> inplaceWrite = vbox.inplace;
	OwnershipRecord currentOwner = inplaceWrite.orec;
	if (currentOwner.owner == this) { // we are already the current writer
	    inplaceWrite.tempValue = (value == null ? (T) NULL_VALUE : value);
	    return;
	}

	while (true) {
	    if (currentOwner.version != 0) {
		if (currentOwner.version <= this.number) {
		    if (inplaceWrite.CASowner(currentOwner, this.orec)) {
			inplaceWrite.tempValue = (value == null ? (T) NULL_VALUE : value);
			boxesWrittenInPlace = boxesWrittenInPlace.cons(vbox);
			return;
		    }
		    currentOwner = inplaceWrite.orec;
		    continue;
		}
		// more recent than my number
		break;
	    } else {
		int versionOnAnc = retrieveAncestorVersion(currentOwner.owner);
		if (versionOnAnc >= 0) {
		    if (vbox.CASinplace(inplaceWrite, new InplaceWrite<T>(this.orec, (value == null ? (T) NULL_VALUE : value),
			    inplaceWrite))) {
			return;
		    }
		    inplaceWrite = vbox.inplace;
		    currentOwner = inplaceWrite.orec;
		    continue;
		} else {
		    Transaction abortUpTo = retrieveLowestCommonAncestor(currentOwner.owner);
		    if (abortUpTo != null) {
			manualAbort();
			int sleepTime = backoffTime.get();
			try {
			    Thread.sleep(sleepTime);
			} catch (InterruptedException e) {
			}
			backoffTime.set(sleepTime * 2);
			throw new CommitException(abortUpTo);
		    }
		    // owner is not from this nesting tree
		    break;
		}
	    }
	}

	manualAbort();
	throw EXECUTE_SEQUENTIALLY_EXCEPTION;
    }

    /*
     * This behavior is different in the case of parallel nested transactions. A
     * PerTxBox avoids reading VBoxes until commit time, to avoid concurrent
     * writes invalidating the read and causing the abort of the transaction.
     * However, in the case of parallel nested transactions, this issue may
     * happen at all levels of nesting. Therefore, it is not clear when a
     * PerTxBox should be committed: in a nested commit, or in the top-level?
     * There are issues with both choices.
     * 
     * Therefore, the solution is the following: Allow reading PerTxValues from
     * an ancestor if no sibling committed in the meantime. Moreover, accesses
     * to the ancestors' perTxValues maps must be synchronized as the underlying
     * map is not thread-safe. Additionally, at commit time, no sibling may have
     * committed if this transaction read some PerTxValue from an ancestor.
     * 
     * In the cases in which we pessimistically guess that the transaction may
     * have read an inconsistent PerTxValue from an ancestor (committed by some
     * sibling), the parallel nested transaction aborts and is repeated by the
     * root top level transaction ancestor sequentially (as in the fallback
     * mechanism of parallel nesting).
     */
    protected boolean readAncestorPerTxValue = false;

    @Override
    protected <T> T getPerTxValue(PerTxBox<T> box) {
	T value = null;
	if (perTxValues != EMPTY_MAP) {
	    value = (T) perTxValues.get(box);
	    if (value != null) {
		return value;
	    }
	}

	ReadWriteTransaction iter = getRWParent();
	while (iter != null) {
	    synchronized (iter) {
		if (iter.perTxValues == EMPTY_MAP) {
		    iter = iter.getRWParent();
		    continue;
		}

		value = (T) iter.perTxValues.get(box);
	    }

	    if (value != null) {
		int versionOnAnc = retrieveAncestorVersion(iter);
		if (iter.nestedVersion != versionOnAnc) {
		    // A nested commit into this ancestor has taken place since
		    // this transaction started. Thus we must pessimistically
		    // assume that this read may not be consistent.
		    manualAbort();
		    throw EXECUTE_SEQUENTIALLY_EXCEPTION;
		}
		this.readAncestorPerTxValue = true;
		return value;
	    }

	    iter = iter.getRWParent();
	}

	return value;
    }

    /*
     * Here we ensure that the array read is consistent with concurrent nested
     * commits
     */
    @Override
    protected <T> T getLocalArrayValue(VArrayEntry<T> entry) {
	if (this.arrayWrites != EMPTY_MAP) {
	    VArrayEntry<T> wsEntry = (VArrayEntry<T>) this.arrayWrites.get(entry);
	    if (wsEntry != null) {
		return (wsEntry.getWriteValue() == null ? (T) NULL_VALUE : wsEntry.getWriteValue());
	    }
	}

	ReadWriteTransaction iter = getRWParent();
	while (iter != null) {
	    if (iter.arrayWrites != EMPTY_MAP) {
		VArrayEntry<T> wsEntry = (VArrayEntry<T>) iter.arrayWrites.get(entry);
		if (wsEntry == null) {
		    iter = iter.getRWParent();
		    continue;
		}

		if (wsEntry.nestedVersion <= retrieveAncestorVersion(iter)) {
		    this.arraysRead = this.arraysRead.cons(entry);
		    entry.setReadOwner(iter);
		    return (wsEntry.getWriteValue() == null ? (T) NULL_VALUE : wsEntry.getWriteValue());
		} else {
		    throw new CommitException(iter);
		}
	    }
	    iter = iter.getRWParent();
	}

	return null;
    }

    @Override
    protected void finish() {
	boxesWritten = null;
	perTxValues = null;
	mergedTxs = null;
    }

    @Override
    protected void doCommit() {
	tryCommit();
	boxesWritten = null;
	perTxValues = EMPTY_MAP;
	mergedTxs = null;
    }

    @Override
    protected void cleanUp() {
	boxesWrittenInPlace = null;
	nestedReads = null;
	for (ReadBlock block : globalReads) {
	    block.free = true;
	    block.freeBlocks.incrementAndGet();
	}
	globalReads = null;

    }

    @Override
    protected void tryCommit() {
	ReadWriteTransaction parent = getRWParent();
	synchronized (parent) {

	    snapshotValidation();

	    // Validate array reads and propagate them to the parent. Only a
	    // subset is propagated.
	    // At this point this transaction can no longer fail, thus the
	    // propagation is correct.
	    parent.arraysRead = validateNestedArrayReads();

	    // Support for PerTxBoxes
	    if (readAncestorPerTxValue) {
		// If some concurrent commit took place into the parent in the
		// meantime, the reads over perTxValues from ancestors may have
		// been made stale. For this reason, we have to pessimistically
		// abort this transaction and execute it sequentially.
		if (retrieveAncestorVersion(parent) != parent.nestedVersion) {
		    manualAbort();
		    throw EXECUTE_SEQUENTIALLY_EXCEPTION;
		}

		// Pessimistically make all ancestors do this verification
		if (parent instanceof ParallelNestedTransaction) {
		    ((ParallelNestedTransaction) parent).readAncestorPerTxValue = true;
		}
	    }

	    int commitVersion = parent.nestedVersion + 1;
	    orec.nestedVersion = commitVersion;
	    orec.owner = parent;
	    Cons<ParallelNestedTransaction> txsAlreadyMerged = parent.mergedTxs.cons(this);
	    for (ParallelNestedTransaction mergedTx : mergedTxs) {
		mergedTx.orec.nestedVersion = commitVersion;
		mergedTx.orec.owner = parent;
		txsAlreadyMerged = txsAlreadyMerged.cons(mergedTx);
	    }
	    parent.mergedTxs = txsAlreadyMerged;

	    if (parent.perTxValues == EMPTY_MAP) {
		parent.perTxValues = perTxValues;
	    } else {
		parent.perTxValues.putAll(perTxValues);
	    }

	    if (parent.arrayWrites == EMPTY_MAP) {
		parent.arrayWrites = arrayWrites;
		parent.arrayWritesCount = arrayWritesCount;
		for (VArrayEntry<?> entry : arrayWrites.values()) {
		    entry.nestedVersion = commitVersion;
		}
	    } else {
		// Propagate arrayWrites and correctly update the parent's
		// arrayWritebacks counter
		for (VArrayEntry<?> entry : arrayWrites.values()) {
		    // Update the array write entry nested version
		    entry.nestedVersion = commitVersion;

		    if (parent.arrayWrites.put(entry, entry) != null)
			continue;

		    // Count number of writes to the array
		    Integer writeCount = parent.arrayWritesCount.get(entry.array);
		    if (writeCount == null)
			writeCount = 0;
		    parent.arrayWritesCount.put(entry.array, writeCount + 1);
		}
	    }

	    // volatile write
	    parent.nestedVersion++;
	}
	backoffTime.set(1);
    }

    protected void snapshotValidation() {
	if (retrieveAncestorVersion(parent) == ((ReadWriteTransaction) parent).nestedVersion) {
	    return;
	}

	for (Map.Entry<VBox, InplaceWrite> read : nestedReads.entrySet()) {
	    validateNestedRead(read);
	}

	for (ParallelNestedTransaction mergedTx : mergedTxs) {
	    for (Map.Entry<VBox, InplaceWrite> read : mergedTx.nestedReads.entrySet()) {
		validateNestedRead(read);
	    }
	}

	if (!this.globalReads.isEmpty()) {
	    validateGlobalReads(globalReads, next);
	}

	for (ParallelNestedTransaction mergedTx : mergedTxs) {
	    if (!mergedTx.globalReads.isEmpty()) {
		validateGlobalReads(mergedTx.globalReads, mergedTx.next);
	    }
	}

    }

    /*
     * Validate a single read that was a read-after-write over some ancestor
     * write. Iterate over the inplace writes of that VBox: if an entry is found
     * belonging to an ancestor, it must be the one that it was read, in which
     * case the search stops.
     */
    protected void validateNestedRead(Map.Entry<VBox, InplaceWrite> read) {
	InplaceWrite inplaceRead = read.getValue();
	InplaceWrite iter = read.getKey().inplace;
	do {
	    if (iter == inplaceRead) {
		break;
	    }
	    int maxVersion = retrieveAncestorVersion(iter.orec.owner);
	    if (maxVersion >= 0) {
		manualAbort();
		throw new CommitException(iter.orec.owner);
	    }
	    iter = iter.next;
	} while (iter != null);
    }

    /*
     * Validate a single read that obtained a VBoxBody Iterate over the inplace
     * writes of that VBox: no entry may be found that belonged to an ancestor
     */
    protected void validateGlobalReads(Cons<ReadBlock> reads, int startIdx) {
	VBox[] array = reads.first().entries;
	// the first may not be full
	for (int i = startIdx + 1; i < array.length; i++) {
	    InplaceWrite iter = array[i].inplace;
	    do {
		int maxVersion = retrieveAncestorVersion(iter.orec.owner);
		if (maxVersion >= 0) {
		    manualAbort();
		    throw new CommitException(iter.orec.owner);
		}
		iter = iter.next;
	    } while (iter != null);
	}

	// the rest are full
	for (ReadBlock block : reads.rest()) {
	    array = block.entries;
	    for (int i = 0; i < array.length; i++) {
		InplaceWrite iter = array[i].inplace;
		do {
		    int maxVersion = retrieveAncestorVersion(iter.orec.owner);
		    if (maxVersion >= 0) {
			manualAbort();
			throw new CommitException(iter.orec.owner);
		    }
		    iter = iter.next;
		} while (iter != null);
	    }
	}
    }

    protected Cons<VArrayEntry<?>> validateNestedArrayReads() {
	Map<VArrayEntry<?>, VArrayEntry<?>> parentArrayWrites = getRWParent().arrayWrites;
	Cons<VArrayEntry<?>> parentArrayReads = getRWParent().arraysRead;
	int maxVersionOnParent = retrieveAncestorVersion(parent);
	for (VArrayEntry<?> entry : arraysRead) {

	    // If the read was performed on an ancestor of the parent, then
	    // propagate it
	    // for further validation
	    if (entry.owner != parent) {
		parentArrayReads = parentArrayReads.cons(entry);
	    }

	    if (parentArrayWrites != EMPTY_MAP) {
		// Verify if the parent contains a more recent write for the
		// read that we performed
		// somewhere in our ancestors
		VArrayEntry<?> parentWrite = parentArrayWrites.get(entry);
		if (parentWrite == null) {
		    continue;
		}
		if (parentWrite.nestedVersion > maxVersionOnParent) {
		    throw new CommitException(parent);
		}
	    }
	}

	return parentArrayReads;
    }
}
