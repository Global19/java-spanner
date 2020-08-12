/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.spanner.connection;

import com.google.api.core.ApiFuture;
import com.google.api.gax.grpc.GrpcCallContext;
import com.google.api.gax.rpc.ApiCallContext;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.SpannerExceptionFactory;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.connection.StatementExecutor.StatementTimeout;
import com.google.cloud.spanner.connection.StatementParser.ParsedStatement;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.Context;
import io.grpc.MethodDescriptor;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/** Base for all {@link Connection}-based transactions and batches. */
abstract class AbstractBaseUnitOfWork implements UnitOfWork {
  private final StatementExecutor statementExecutor;
  private final StatementTimeout statementTimeout;

  /**
   * The {@link Future} that monitors the result of the statement currently being executed for this
   * unit of work.
   */
  @GuardedBy("this")
  private Future<?> currentlyRunningStatementFuture = null;

  enum InterceptorsUsage {
    INVOKE_INTERCEPTORS,
    IGNORE_INTERCEPTORS;
  }

  abstract static class Builder<B extends Builder<?, T>, T extends AbstractBaseUnitOfWork> {
    private StatementExecutor statementExecutor;
    private StatementTimeout statementTimeout = new StatementTimeout();

    Builder() {}

    @SuppressWarnings("unchecked")
    B self() {
      return (B) this;
    }

    B withStatementExecutor(StatementExecutor executor) {
      Preconditions.checkNotNull(executor);
      this.statementExecutor = executor;
      return self();
    }

    B setStatementTimeout(StatementTimeout timeout) {
      Preconditions.checkNotNull(timeout);
      this.statementTimeout = timeout;
      return self();
    }

    abstract T build();
  }

  AbstractBaseUnitOfWork(Builder<?, ?> builder) {
    Preconditions.checkState(builder.statementExecutor != null, "No statement executor specified");
    this.statementExecutor = builder.statementExecutor;
    this.statementTimeout = builder.statementTimeout;
  }

  StatementExecutor getStatementExecutor() {
    return statementExecutor;
  }

  StatementTimeout getStatementTimeout() {
    return statementTimeout;
  }

  @Override
  public void cancel() {
    synchronized (this) {
      if (currentlyRunningStatementFuture != null
          && !currentlyRunningStatementFuture.isDone()
          && !currentlyRunningStatementFuture.isCancelled()) {
        currentlyRunningStatementFuture.cancel(true);
      }
    }
  }

  <T> T executeStatement(
      ParsedStatement statement,
      Callable<T> callable,
      @Nullable MethodDescriptor<?, ?> applyStatementTimeoutToMethod) {
    return executeStatement(
        statement, callable, InterceptorsUsage.INVOKE_INTERCEPTORS, applyStatementTimeoutToMethod);
  }

  <T> ApiFuture<T> executeStatementAsync(
      ParsedStatement statement,
      Callable<T> callable,
      @Nullable MethodDescriptor<?, ?> applyStatementTimeoutToMethod) {
    return executeStatementAsync(
        statement,
        callable,
        InterceptorsUsage.INVOKE_INTERCEPTORS,
        applyStatementTimeoutToMethod == null
            ? Collections.<MethodDescriptor<?, ?>>emptySet()
            : ImmutableList.<MethodDescriptor<?, ?>>of(applyStatementTimeoutToMethod));
  }

  <T> ApiFuture<T> executeStatementAsync(
      ParsedStatement statement,
      Callable<T> callable,
      Collection<MethodDescriptor<?, ?>> applyStatementTimeoutToMethods) {
    return executeStatementAsync(
        statement, callable, InterceptorsUsage.INVOKE_INTERCEPTORS, applyStatementTimeoutToMethods);
  }

  <T> T getWithStatementTimeout(ApiFuture<T> future, ParsedStatement statement) {
    T res;
    try {
      // TODO: Remove this and rely on RPC timeouts.
      if (statementTimeout.hasTimeout()) {
        TimeUnit unit = statementTimeout.getAppropriateTimeUnit();
        res = future.get(statementTimeout.getTimeoutValue(unit), unit);
      } else {
        res = future.get();
      }
    } catch (TimeoutException e) {
      // statement timed out, cancel the execution
      future.cancel(true);
      throw SpannerExceptionFactory.newSpannerException(
          ErrorCode.DEADLINE_EXCEEDED,
          "Statement execution timeout occurred for " + statement.getSqlWithoutComments(),
          e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      Set<Throwable> causes = new HashSet<>();
      while (cause != null && !causes.contains(cause)) {
        if (cause instanceof SpannerException) {
          throw (SpannerException) cause;
        }
        causes.add(cause);
        cause = cause.getCause();
      }
      throw SpannerExceptionFactory.newSpannerException(
          ErrorCode.UNKNOWN,
          "Statement execution failed for " + statement.getSqlWithoutComments(),
          e);
    } catch (InterruptedException e) {
      throw SpannerExceptionFactory.newSpannerException(
          ErrorCode.CANCELLED, "Statement execution was interrupted", e);
    } catch (CancellationException e) {
      throw SpannerExceptionFactory.newSpannerException(
          ErrorCode.CANCELLED, "Statement execution was cancelled", e);
    }
    return res;
  }

  <T> T executeStatement(
      ParsedStatement statement,
      Callable<T> callable,
      InterceptorsUsage interceptorUsage,
      @Nullable MethodDescriptor<?, ?> applyStatementTimeoutToMethod) {
    Future<T> future =
        executeStatementAsync(
            statement,
            callable,
            interceptorUsage,
            applyStatementTimeoutToMethod == null
                ? Collections.<MethodDescriptor<?, ?>>emptySet()
                : ImmutableList.<MethodDescriptor<?, ?>>of(applyStatementTimeoutToMethod));
    T res;
    try {
      if (statementTimeout.hasTimeout()) {
        TimeUnit unit = statementTimeout.getAppropriateTimeUnit();
        res = future.get(statementTimeout.getTimeoutValue(unit), unit);
      } else {
        res = future.get();
      }
    } catch (TimeoutException e) {
      // statement timed out, cancel the execution
      future.cancel(true);
      throw SpannerExceptionFactory.newSpannerException(
          ErrorCode.DEADLINE_EXCEEDED,
          "Statement execution timeout occurred for " + statement.getSqlWithoutComments(),
          e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      Set<Throwable> causes = new HashSet<>();
      while (cause != null && !causes.contains(cause)) {
        if (cause instanceof SpannerException) {
          throw (SpannerException) cause;
        }
        causes.add(cause);
        cause = cause.getCause();
      }
      throw SpannerExceptionFactory.newSpannerException(
          ErrorCode.UNKNOWN,
          "Statement execution failed for " + statement.getSqlWithoutComments(),
          e);
    } catch (InterruptedException e) {
      throw SpannerExceptionFactory.newSpannerException(
          ErrorCode.CANCELLED, "Statement execution was interrupted", e);
    } catch (CancellationException e) {
      throw SpannerExceptionFactory.newSpannerException(
          ErrorCode.CANCELLED, "Statement execution was cancelled", e);
    } finally {
      synchronized (this) {
        this.currentlyRunningStatementFuture = null;
      }
    }
    return res;
  }

  <T> ApiFuture<T> executeStatementAsync(
      ParsedStatement statement,
      Callable<T> callable,
      InterceptorsUsage interceptorUsage,
      final Collection<MethodDescriptor<?, ?>> applyStatementTimeoutToMethods) {
    Preconditions.checkNotNull(statement);
    Preconditions.checkNotNull(callable);

    if (interceptorUsage == InterceptorsUsage.INVOKE_INTERCEPTORS) {
      statementExecutor.invokeInterceptors(
          statement, StatementExecutionStep.EXECUTE_STATEMENT, this);
    }
    Context context = Context.current();
    if (statementTimeout.hasTimeout() && !applyStatementTimeoutToMethods.isEmpty()) {
      context =
          context.withValue(
              SpannerOptions.CALL_CONTEXT_CONFIGURATOR_KEY,
              new SpannerOptions.CallContextConfigurator() {
                @Override
                public <ReqT, RespT> ApiCallContext configure(
                    ApiCallContext context, ReqT request, MethodDescriptor<ReqT, RespT> method) {
                  if (statementTimeout.hasTimeout()
                      && applyStatementTimeoutToMethods.contains(method)) {
                    return GrpcCallContext.createDefault()
                        .withTimeout(statementTimeout.asDuration());
                  }
                  return null;
                }
              });
    }
    ApiFuture<T> future = statementExecutor.submit(context.wrap(callable));
    synchronized (this) {
      this.currentlyRunningStatementFuture = future;
    }
    future.addListener(
        new Runnable() {
          @Override
          public void run() {
            synchronized (this) {
              currentlyRunningStatementFuture = null;
            }
          }
        },
        MoreExecutors.directExecutor());
    return future;
  }
}
