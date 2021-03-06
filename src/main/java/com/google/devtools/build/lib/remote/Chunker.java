// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.remote;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import build.bazel.remote.execution.v2.Digest;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.vfs.Path;
import com.google.protobuf.ByteString;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Splits a data source into one or more {@link Chunk}s of at most {@code chunkSize} bytes.
 *
 * <p>After a data source has been fully consumed, that is until {@link #hasNext()} returns
 * {@code false}, the chunker closes the underlying data source (i.e. file) itself. However, in
 * case of error or when a data source does not get fully consumed, a user must call
 * {@link #reset()} manually.
 */
public final class Chunker {

  private static int defaultChunkSize = 1024 * 16;

  /** This method must only be called in tests! */
  @VisibleForTesting
  static void setDefaultChunkSizeForTesting(int value) {
    defaultChunkSize = value;
  }

  static int getDefaultChunkSize() {
    return defaultChunkSize;
  }

  /** A piece of a byte[] blob. */
  public static final class Chunk {

    private final Digest digest;
    private final long offset;
    private final ByteString data;

    private Chunk(Digest digest, ByteString data, long offset) {
      this.digest = digest;
      this.data = data;
      this.offset = offset;
    }

    public Digest getDigest() {
      return digest;
    }

    public long getOffset() {
      return offset;
    }

    public ByteString getData() {
      return data;
    }

    @Override
    public boolean equals(Object o) {
      if (o == this) {
        return true;
      }
      if (!(o instanceof Chunk)) {
        return false;
      }
      Chunk other = (Chunk) o;
      return other.offset == offset
          && other.digest.equals(digest)
          && other.data.equals(data);
    }

    @Override
    public int hashCode() {
      return Objects.hash(digest, offset, data);
    }
  }

  private final Supplier<InputStream> dataSupplier;
  private final Digest digest;
  private final int chunkSize;
  private final Chunk emptyChunk;

  private InputStream data;
  private long offset;
  private byte[] chunkCache;

  // Set to true on the first call to next(). This is so that the Chunker can open its data source
  // lazily on the first call to next(), as opposed to opening it in the constructor or on reset().
  private boolean initialized;

  Chunker(Supplier<InputStream> dataSupplier, Digest digest, int chunkSize, DigestUtil digestUtil) {
    this.dataSupplier = checkNotNull(dataSupplier);
    this.digest = checkNotNull(digest);
    this.chunkSize = chunkSize;
    this.emptyChunk = new Chunk(digestUtil.compute(new byte[0]), ByteString.EMPTY, 0);
  }

  public Digest digest() {
    return digest;
  }

  /**
   * Reset the {@link Chunker} state to when it was newly constructed.
   *
   * <p>Closes any open resources (file handles, ...).
   */
  public void reset() throws IOException {
    if (data != null) {
      data.close();
    }
    data = null;
    offset = 0;
    initialized = false;
    chunkCache = null;
  }

  /**
   * Returns {@code true} if a subsequent call to {@link #next()} returns a {@link Chunk} object;
   */
  public boolean hasNext() {
    return data != null || !initialized;
  }

  /**
   * Returns the next {@link Chunk} or throws a {@link NoSuchElementException} if no data is left.
   *
   * <p>Always call {@link #hasNext()} before calling this method.
   *
   * <p>Zero byte inputs are treated special. Instead of throwing a {@link NoSuchElementException}
   * on the first call to {@link #next()}, a {@link Chunk} with an empty {@link ByteString} is
   * returned.
   */
  public Chunk next() throws IOException {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }

    maybeInitialize();

    if (digest.getSizeBytes() == 0) {
      data = null;
      return emptyChunk;
    }

    // The cast to int is safe, because the return value is capped at chunkSize.
    int bytesToRead = (int) Math.min(bytesLeft(), chunkSize);
    if (bytesToRead == 0) {
      chunkCache = null;
      data = null;
      throw new NoSuchElementException();
    }

    if (chunkCache == null) {
      // Lazily allocate it in order to save memory on small data.
      // 1) bytesToRead < chunkSize: There will only ever be one next() call.
      // 2) bytesToRead == chunkSize: chunkCache will be set to its biggest possible value.
      // 3) bytestoRead > chunkSize: Not possible, due to Math.min above.
      chunkCache = new byte[bytesToRead];
    }

    long offsetBefore = offset;
    try {
      ByteStreams.readFully(data, chunkCache, 0, bytesToRead);
    } catch (EOFException e) {
      throw new IllegalStateException("Reached EOF, but expected "
          + bytesToRead + " bytes.", e);
    }
    offset += bytesToRead;

    ByteString blob = ByteString.copyFrom(chunkCache, 0, bytesToRead);

    if (bytesLeft() == 0) {
      data.close();
      data = null;
      chunkCache = null;
    }

    return new Chunk(digest, blob, offsetBefore);
  }

  private long bytesLeft() {
    return digest.getSizeBytes() - offset;
  }

  private void maybeInitialize() throws IOException {
    if (initialized) {
      return;
    }
    checkState(data == null);
    checkState(offset == 0);
    checkState(chunkCache == null);
    try {
      data = dataSupplier.get();
    } catch (RuntimeException e) {
      Throwables.propagateIfPossible(e.getCause(), IOException.class);
      throw e;
    }
    initialized = true;
  }

  public static Builder builder(DigestUtil digestUtil) {
    return new Builder(digestUtil);
  }

  /** Builder class for the Chunker */
  public static class Builder {
    private final DigestUtil digestUtil;
    private int chunkSize = getDefaultChunkSize();
    private Digest digest;
    private Supplier<InputStream> inputStream;

    Builder(DigestUtil digestUtil) {
      this.digestUtil = digestUtil;
    }

    public Builder setInput(byte[] data) {
      Preconditions.checkState(inputStream == null);
      digest = digestUtil.compute(data);
      inputStream = () -> new ByteArrayInputStream(data);
      return this;
    }

    public Builder setInput(Digest digest, byte[] data) {
      Preconditions.checkState(inputStream == null);
      this.digest = digest;
      inputStream = () -> new ByteArrayInputStream(data);
      return this;
    }

    public Builder setInput(Digest digest, Path file) {
      Preconditions.checkState(inputStream == null);
      this.digest = digest;
      inputStream =
          () -> {
            try {
              return file.getInputStream();
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          };
      return this;
    }

    public Builder setInput(Digest digest, ActionInput actionInput, Path execRoot) {
      Preconditions.checkState(inputStream == null);
      this.digest = digest;
      if (actionInput instanceof VirtualActionInput) {
        this.inputStream =
            () -> {
              try {
                return ((VirtualActionInput) actionInput).getBytes().newInput();
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            };
      } else {
        inputStream =
            () -> {
              try {
                return execRoot.getRelative(actionInput.getExecPathString()).getInputStream();
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            };
      }
      return this;
    }

    public Builder setChunkSize(int chunkSize) {
      this.chunkSize = chunkSize;
      return this;
    }

    public Chunker build() {
      Preconditions.checkNotNull(inputStream, digest);
      return new Chunker(inputStream, digest, chunkSize, digestUtil);
    }
  }
}
