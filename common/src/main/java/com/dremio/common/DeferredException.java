/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.common;

import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;

/**
 * Collects one or more exceptions that may occur, using
 * <a href="http://docs.oracle.com/javase/tutorial/essential/exceptions/tryResourceClose.html#suppressed-exceptions">
 * suppressed exceptions</a>.
 * When this AutoCloseable is closed, if there was an exception added, it will be thrown. If more than one
 * exception was added, then all but the first will be added to the first as suppressed
 * exceptions.
 *
 * <p>This class is thread safe.
 */
public class DeferredException implements AutoCloseable {

  private Exception exception = null;
  private boolean isClosed = false;
  private final Supplier<Exception> exceptionSupplier;
  private final AtomicInteger suppressedExceptions = new AtomicInteger(0);
  private static final int LIMIT_SUPRESSED_EXCEPTION = 5;

  /**
   * Constructor.
   */
  public DeferredException() {
    this(null);
  }

  /**
   * Constructor. This constructor accepts a Supplier that can be used
   * to create the root exception iff any other exceptions are added. For
   * example, in a series of resources closures in a close(), if any of
   * the individual closures fails, the root exception should come from
   * the current class, not from the first subordinate close() to fail.
   * This can be used to provide an exception in that case which will be
   * the root exception; the subordinate failed close() will be added to
   * that exception as a suppressed exception.
   *
   * @param exceptionSupplier lazily supplies what will be the root exception
   *   if any exceptions are added
   */
  public DeferredException(Supplier<Exception> exceptionSupplier) {
    this.exceptionSupplier = exceptionSupplier;
  }

  /**
   * Add an exception. If this is the first exception added, it will be the one
   * that is thrown when this is closed. If not the first exception, then it will
   * be added to the suppressed exceptions on the first exception.
   *
   * @param exception the exception to add
   */
  public void addException(final Exception exception) {
    Preconditions.checkNotNull(exception);

    if (suppressedExceptions.addAndGet(1) > (LIMIT_SUPRESSED_EXCEPTION)) {
      return;
    }

    Exception exceptionToAdd = limitSuppressedExeptions(exception);

    synchronized(this) {
      Preconditions.checkState(!isClosed);

      if (this.exception == null) {
        if (exceptionSupplier == null) {
          this.exception = exceptionToAdd;
        } else {
          this.exception = exceptionSupplier.get();
          if (this.exception == null) {
            this.exception = new RuntimeException("Missing root exception");
          }
          this.exception.addSuppressed(exceptionToAdd);
        }
      } else if (this.exception != exception) { //Self-suppression is not permitted
        this.exception.addSuppressed(exceptionToAdd);
      }
    }
  }

  private Exception limitSuppressedExeptions(Exception exception) {
    Exception exception1 = exception;
    final Throwable[] suppressed = exception.getSuppressed();
    // strip down the suppressed exception to max
    if (suppressed.length > LIMIT_SUPRESSED_EXCEPTION) {
      exception1 = new Exception(exception.getMessage(), exception.getCause());
      for (int i = 0; i < LIMIT_SUPRESSED_EXCEPTION; i++) {
        // walk down the chain and strip everything down
        exception1.addSuppressed(limitSuppressedExeptions(suppressed[i]));
      }
    }
    return exception1;
  }

  private Throwable limitSuppressedExeptions(Throwable exception) {
    Throwable exception1 = exception;
    final Throwable[] suppressed = exception.getSuppressed();
    // strip down the suppressed exception to max
    if (suppressed.length > LIMIT_SUPRESSED_EXCEPTION) {
      exception1 = new Exception(exception.getMessage(), exception.getCause());
      for (int i = 0; i < LIMIT_SUPRESSED_EXCEPTION; i++) {
        exception1.addSuppressed(limitSuppressedExeptions(suppressed[i]));
      }
    }
    return exception1;
  }

  public void addThrowable(final Throwable throwable) {
    Preconditions.checkNotNull(throwable);

    if (throwable instanceof Exception) {
      addException((Exception) throwable);
      return;
    }

    addException(new RuntimeException(throwable));
  }

  /**
   * Get the deferred exception, if there is one. Note that if this returns null,
   * the result could change at any time.
   *
   * @return the deferred exception, or null
   */
  public synchronized Exception getException() {
    return exception;
  }

  public synchronized boolean hasException() {
    return exception != null;
  }

  public synchronized Exception getAndClear() {
    Preconditions.checkState(!isClosed);

    if (exception != null) {
      final Exception local = exception;
      exception = null;
      return local;
    }

    return null;
  }

  /**
   * If an exception exists, will throw the exception and then clear it. This is so in cases where want to reuse
   * DeferredException, we don't double report the same exception.
   *
   * @throws Exception
   */
  public synchronized void throwAndClear() throws Exception {
    final Exception e = getAndClear();
    if (e != null) {
      throw e;
    }
  }

  /**
   * If an exception exists, will throw the exception and then clear it. This is so in cases where want to reuse
   * DeferredException, we don't double report the same exception.
   *
   * @throws Exception
   */
  public synchronized void throwAndClearRuntime() {
    final Exception e = getAndClear();
    if(e != null){
      throw Throwables.propagate(e);
    }
  }

  public synchronized void throwNoClearRuntime() {
    final Exception e = getException();
    if(e != null){
      throw Throwables.propagate(e);
    }
  }

  /**
   * Close the given AutoCloseable, suppressing any exceptions that are thrown.
   * If an exception is thrown, the rules for {@link #addException(Exception)}
   * are followed.
   *
   * @param autoCloseable the AutoCloseable to close; may be null
   */
  public void suppressingClose(final AutoCloseable autoCloseable) {
    /*
     * For the sake of detecting code that doesn't follow the conventions,
     * we want this to complain whether the closeable exists or not.
     */
    Preconditions.checkState(!isClosed);

    if (autoCloseable == null) {
      return;
    }

    try {
      autoCloseable.close();
    } catch(final Exception e) {
      addException(e);
    }
  }

  @Override
  public synchronized void close() throws Exception {
    try {
      throwAndClear();
    } finally {
      isClosed = true;
    }
  }
}
