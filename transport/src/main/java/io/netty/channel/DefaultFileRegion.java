/*
 * Copyright 2012 The Netty Project
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

import io.netty.util.AbstractReferenceCounted;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ReferenceCounted;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;

/**
 * Default {@link FileRegion} implementation which transfer data from a {@link FileChannel} or {@link File}.
 *
 * Be aware that the {@link FileChannel} will be automatically closed once {@link #refCnt()} returns
 * {@code 0}.
 */
public class DefaultFileRegion extends AbstractFileRegion implements FileRegion {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultFileRegion.class);
    private final File f;
    private final long position;
    private final long count;
    private long transfered;
    private FileChannel file;

    /**
     * Create a new instance
     *
     * @param file      the {@link FileChannel} which should be transfered
     * @param position  the position from which the transfer should start
     * @param count     the number of bytes to transfer
     */
    public DefaultFileRegion(FileChannel file, long position, long count) {
        if (file == null) {
            throw new NullPointerException("file");
        }
        if (position < 0) {
            throw new IllegalArgumentException("position must be >= 0 but was " + position);
        }
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0 but was " + count);
        }
        this.file = file;
        this.position = position;
        this.count = count;
        f = null;
    }

    /**
     * Create a new instance using the given {@link File}. The {@link File} will be opened lazily or
     * explicitly via {@link #open()}.
     *
     * @param f         the {@link File} which should be transfered
     * @param position  the position from which the transfer should start
     * @param count     the number of bytes to transfer
     */
    public DefaultFileRegion(File f, long position, long count) {
        if (f == null) {
            throw new NullPointerException("f");
        }
        if (position < 0) {
            throw new IllegalArgumentException("position must be >= 0 but was " + position);
        }
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0 but was " + count);
        }
        this.position = position;
        this.count = count;
        this.f = f;
    }

    /**
     * Returns {@code true} if the {@link FileRegion} has a open file-descriptor
     */
    public boolean isOpen() {
        return file != null;
    }

    /**
     * Explicitly open the underlying file-descriptor if not done yet.
     */
    public void open() throws IOException {
        if (!isOpen() && refCnt() > 0) {
            // Only open if this DefaultFileRegion was not released yet.
            file = new RandomAccessFile(f, "r").getChannel();
        }
    }

    @Override
    public long position() {
        return position;
    }

    @Override
    public long count() {
        return count;
    }

    @Override
    public long transfered() {
        return transfered;
    }

    @Override
    public long transferTo(WritableByteChannel target, long position) throws IOException {
        long count = this.count - position;
        if (count < 0 || position < 0) {
            throw new IllegalArgumentException(
                    "position out of range: " + position +
                    " (expected: 0 - " + (this.count - 1) + ')');
        }
        if (count == 0) {
            return 0L;
        }
        if (refCnt() == 0) {
            throw new IllegalReferenceCountException(0);
        }
        // Call open to make sure fc is initialized. This is a no-oop if we called it before.
        open();

        long written = file.transferTo(this.position + position, count, target);
        if (written > 0) {
            transfered += written;
        }
        return written;
    }

    private final AbstractReferenceCounted refCnt = new AbstractReferenceCounted() {

        @Override
        public ReferenceCounted touch(Object hint) {
            return DefaultFileRegion.this;
        }

        @Override
        protected void deallocate() {
            close();
        }
    };

    private void close() {
        FileChannel file = this.file;

        if (file == null) {
            return;
        }
        this.file = null;

        try {
            file.close();
        } catch (IOException e) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close a file.", e);
            }
        }
    }

    @Override
    public int refCnt() {
        return refCnt.refCnt();
    }

    @Override
    public boolean release() {
        return refCnt.release();
    }

    @Override
    public boolean release(int decrement) {
        return refCnt.release(decrement);
    }

    @Override
    public DefaultFileRegion retain() {
        refCnt.retain();
        return this;
    }

    @Override
    public DefaultFileRegion retain(int increment) {
        refCnt.retain(increment);
        return this;
    }

    @Override
    public DefaultFileRegion touch() {
        refCnt.touch();
        return this;
    }

    @Override
    public DefaultFileRegion touch(Object hint) {
        refCnt.touch(hint);
        return this;
    }

    @Override
    public FileRegion unwrap() {
        return null;
    }

    @Override
    public FileChannel channel() throws IOException {
        if (refCnt() == 0) {
            throw new IllegalReferenceCountException(0);
        }
        open();
        return file;
    }
}
