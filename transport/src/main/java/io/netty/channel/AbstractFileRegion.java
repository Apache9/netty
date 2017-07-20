/*
 * Copyright 2017 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;

/**
 * Base class for {@link FileRegion} implementation.
 *
 */
public abstract class AbstractFileRegion implements FileRegion {

    private long transferIndex;

    /**
     * Transfers the content of this file region to the specified channel.
     *
     * @param target
     *            the destination of the transfer
     * @param length
     *            the maximum number of bytes to transfer
     */
    public long transferBytesTo(WritableByteChannel target, long length) throws IOException {
        long written = transferBytesTo(target, transferIndex, length);
        if (written > 0) {
            transferIndex += written;
        }
        return written;
    }

    /**
     * Returns the {@code transferIndex} of this file region.
     */
    public long transferIndex() {
        return transferIndex;
    }

    /**
     * Sets the {@code transferIndex} of file region.
     *
     * @throws IndexOutOfBoundsException
     *             if the specified {@code transferIndex} is less than {@code 0} or greater than
     *             {@link #count()}
     */
    public FileRegion transferIndex(long index) {
        if (index < 0 || index > count()) {
            throw new IndexOutOfBoundsException(
                    String.format("transferIndex: %d (expected: 0 <= transferIndex <= count(%d))",
                            index, count()));
        }
        this.transferIndex = index;
        return this;
    }

    /**
     * Returns {@code true} if and only if {@link #transferableBytes()} is greater than {@code 0}.
     */
    public boolean isTransferable() {
        return transferIndex < count();
    }

    /**
     * Returns the number of readable bytes which is equal to {@code (count - transferIndex)}.
     */
    public long transferableBytes() {
        return count() - transferIndex;
    }

    /**
     * Transfers the content of this file region to the specified channel.
     * <p>
     * This method does not modify {@link #transferIndex()}.
     *
     * @param target
     *            the destination of the transfer
     * @param position
     *            the relative offset of the file where the transfer begins from. For example,
     *            <tt>0</tt> will make the transfer start from {@link #position()}th byte and
     *            <tt>{@link #count()} - 1</tt> will make the last byte of the region transferred.
     * @param length
     *            the maximum number of bytes to transfer
     */
    public long transferBytesTo(WritableByteChannel target, long position, long length)
            throws IOException {
        if (position < 0 || position > count()) {
            throw new IllegalArgumentException("position out of range: " + position
                    + " (expected: 0 - " + (this.count() - 1) + ')');
        }
        if (length < 0) {
            throw new IllegalArgumentException("negative length " + length);
        }
        length = Math.min(count() - position, length);
        if (length == 0) {
            return 0L;
        }
        return channel().transferTo(position() + position, length, target);
    }

    /**
     * Returns a slice of this file region's sub-region. This method does not modify
     * {@code transferIndex} of this buffer.
     * <p>
     * Also be aware that this method will NOT call {@link #retain()} and so the reference count
     * will NOT be increased.
     */
    public AbstractFileRegion slice(long index, long length) {
        return new SlicedFileRegion(this, index, length);
    }

    /**
     * Returns a slice of this file region's sub-region. This method does not modify
     * {@code transferIndex} of this buffer.
     * <p>
     * Note that this method returns a {@linkplain #retain() retained} file region unlike
     * {@link #slice()}.
     */
    public AbstractFileRegion retainedSlice(long index, long length) {
        AbstractFileRegion sliced = slice(index, length);
        sliced.retain();
        return sliced;
    }

    /**
     * Returns a new slice of this file region's sub-region starting at the current
     * {@code transferIndex} and increases the {@code transferIndex} by the size of the new slice (=
     * {@code length}).
     * <p>
     * Also be aware that this method will NOT call {@link #retain()} and so the reference count
     * will NOT be increased.
     *
     * @param length
     *            the size of the new slice
     */
    public AbstractFileRegion transferSlice(long length) {
        AbstractFileRegion sliced = slice(transferIndex(), length);
        transferIndex += length;
        return sliced;
    }

    /**
     * Returns a new slice of this file region's sub-region starting at the current
     * {@code transferIndex} and increases the {@code transferIndex} by the size of the new slice (=
     * {@code length}).
     * <p>
     * Note that this method returns a {@linkplain #retain() retained} file region unlike
     * {@link #transferSlice()}.
     *
     * @param length
     *            the size of the new slice
     */
    public AbstractFileRegion transferRetainedSlice(long length) {
        AbstractFileRegion sliced = transferSlice(length);
        sliced.retain();
        return sliced;
    }

    /**
     * Return the underlying file region instance if this file region is a wrapper of another file
     * region.
     *
     * @return {@code null} if this file region is not a wrapper
     */
    public abstract AbstractFileRegion unwrap();

    /**
     * Return the underlying file channel instance of this file region.
     */
    public abstract FileChannel channel() throws IOException;
}
