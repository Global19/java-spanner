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

import static com.google.cloud.spanner.SpannerApiFutures.get;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.api.core.SettableApiFuture;
import com.google.api.gax.longrunning.OperationFuture;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.AbortedException;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Options.QueryOption;
import com.google.cloud.spanner.ReadOnlyTransaction;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.SpannerApiFutures;
import com.google.cloud.spanner.SpannerBatchUpdateException;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.SpannerExceptionFactory;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.TimestampBound;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.TransactionManager;
import com.google.cloud.spanner.TransactionRunner;
import com.google.cloud.spanner.TransactionRunner.TransactionCallable;
import com.google.cloud.spanner.connection.StatementParser.ParsedStatement;
import com.google.cloud.spanner.connection.StatementParser.StatementType;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.spanner.admin.database.v1.UpdateDatabaseDdlMetadata;
import com.google.spanner.v1.SpannerGrpc;
import io.grpc.MethodDescriptor;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Transaction that is used when a {@link Connection} is in autocommit mode. Each method on this
 * transaction actually starts a new transaction on Spanner. The type of transaction that is started
 * depends on the type of statement that is being executed. A {@link SingleUseTransaction} will
 * always try to choose the most efficient type of one-time transaction that is available for the
 * statement.
 *
 * <p>A {@link SingleUseTransaction} can be used to execute any type of statement on Cloud Spanner:
 *
 * <ul>
 *   <li>Client side statements, e.g. SHOW VARIABLE AUTOCOMMIT
 *   <li>Queries, e.g. SELECT * FROM FOO
 *   <li>DML statements, e.g. UPDATE FOO SET BAR=1
 *   <li>DDL statements, e.g. CREATE TABLE FOO (...)
 * </ul>
 */
class SingleUseTransaction extends AbstractBaseUnitOfWork {
  private final boolean readOnly;
  private final DdlClient ddlClient;
  private final DatabaseClient dbClient;
  private final TimestampBound readOnlyStaleness;
  private final AutocommitDmlMode autocommitDmlMode;
  private volatile SettableApiFuture<Timestamp> readTimestamp = null;
  private volatile TransactionManager txManager;
  private volatile TransactionRunner writeTransaction;
  private boolean used = false;
  private volatile UnitOfWorkState state = UnitOfWorkState.STARTED;

  static class Builder extends AbstractBaseUnitOfWork.Builder<Builder, SingleUseTransaction> {
    private DdlClient ddlClient;
    private DatabaseClient dbClient;
    private boolean readOnly;
    private TimestampBound readOnlyStaleness;
    private AutocommitDmlMode autocommitDmlMode;

    private Builder() {}

    Builder setDdlClient(DdlClient ddlClient) {
      Preconditions.checkNotNull(ddlClient);
      this.ddlClient = ddlClient;
      return this;
    }

    Builder setDatabaseClient(DatabaseClient client) {
      Preconditions.checkNotNull(client);
      this.dbClient = client;
      return this;
    }

    Builder setReadOnly(boolean readOnly) {
      this.readOnly = readOnly;
      return this;
    }

    Builder setReadOnlyStaleness(TimestampBound staleness) {
      Preconditions.checkNotNull(staleness);
      this.readOnlyStaleness = staleness;
      return this;
    }

    Builder setAutocommitDmlMode(AutocommitDmlMode dmlMode) {
      Preconditions.checkNotNull(dmlMode);
      this.autocommitDmlMode = dmlMode;
      return this;
    }

    @Override
    SingleUseTransaction build() {
      Preconditions.checkState(ddlClient != null, "No DDL client specified");
      Preconditions.checkState(dbClient != null, "No DatabaseClient client specified");
      Preconditions.checkState(readOnlyStaleness != null, "No read-only staleness specified");
      Preconditions.checkState(autocommitDmlMode != null, "No autocommit dml mode specified");
      return new SingleUseTransaction(this);
    }
  }

  static Builder newBuilder() {
    return new Builder();
  }

  private SingleUseTransaction(Builder builder) {
    super(builder);
    this.ddlClient = builder.ddlClient;
    this.dbClient = builder.dbClient;
    this.readOnly = builder.readOnly;
    this.readOnlyStaleness = builder.readOnlyStaleness;
    this.autocommitDmlMode = builder.autocommitDmlMode;
  }

  @Override
  public Type getType() {
    return Type.TRANSACTION;
  }

  @Override
  public UnitOfWorkState getState() {
    return state;
  }

  @Override
  public boolean isActive() {
    // Single-use transactions are never active as they can be used only once.
    return false;
  }

  @Override
  public boolean isReadOnly() {
    return readOnly;
  }

  private void checkAndMarkUsed() {
    Preconditions.checkState(!used, "This single-use transaction has already been used");
    used = true;
  }

  @Override
  public ResultSet executeQuery(
      final ParsedStatement statement,
      final AnalyzeMode analyzeMode,
      final QueryOption... options) {
    return get(executeQueryAsync(statement, analyzeMode, options));
  }

  @Override
  public ApiFuture<ResultSet> executeQueryAsync(
      final ParsedStatement statement,
      final AnalyzeMode analyzeMode,
      final QueryOption... options) {
    Preconditions.checkNotNull(statement);
    Preconditions.checkArgument(statement.isQuery(), "Statement is not a query");
    checkAndMarkUsed();

    final ReadOnlyTransaction currentTransaction =
        dbClient.singleUseReadOnlyTransaction(readOnlyStaleness);
    Callable<ResultSet> callable =
        new Callable<ResultSet>() {
          @Override
          public ResultSet call() throws Exception {
            ResultSet rs;
            if (analyzeMode == AnalyzeMode.NONE) {
              rs = currentTransaction.executeQuery(statement.getStatement(), options);
            } else {
              rs =
                  currentTransaction.analyzeQuery(
                      statement.getStatement(), analyzeMode.getQueryAnalyzeMode());
            }
            // Return a DirectExecuteResultSet, which will directly do a next() call in order to
            // ensure that the query is actually sent to Spanner.
            return DirectExecuteResultSet.ofResultSet(rs);
          }
        };
    ApiFuture<ResultSet> res =
        executeStatementAsync(statement, callable, SpannerGrpc.getExecuteStreamingSqlMethod());
    readTimestamp = SettableApiFuture.create();
    ApiFutures.addCallback(
        res,
        new ApiFutureCallback<ResultSet>() {
          @Override
          public void onFailure(Throwable t) {
            state = UnitOfWorkState.COMMIT_FAILED;
            readTimestamp.set(null);
            currentTransaction.close();
          }

          @Override
          public void onSuccess(ResultSet result) {
            state = UnitOfWorkState.COMMITTED;
            readTimestamp.set(currentTransaction.getReadTimestamp());
          }
        },
        MoreExecutors.directExecutor());
    return res;
  }

  @Override
  public Timestamp getReadTimestamp() {
    ConnectionPreconditions.checkState(
        SpannerApiFutures.getOrNull(readTimestamp) != null,
        "There is no read timestamp available for this transaction.");
    return SpannerApiFutures.get(readTimestamp);
  }

  @Override
  public Timestamp getReadTimestampOrNull() {
    return SpannerApiFutures.getOrNull(readTimestamp);
  }

  private boolean hasCommitTimestamp() {
    return writeTransaction != null
        || (txManager != null
            && txManager.getState()
                == com.google.cloud.spanner.TransactionManager.TransactionState.COMMITTED);
  }

  @Override
  public Timestamp getCommitTimestamp() {
    ConnectionPreconditions.checkState(
        hasCommitTimestamp(), "There is no commit timestamp available for this transaction.");
    return writeTransaction != null
        ? writeTransaction.getCommitTimestamp()
        : txManager.getCommitTimestamp();
  }

  @Override
  public Timestamp getCommitTimestampOrNull() {
    if (hasCommitTimestamp()) {
      try {
        return writeTransaction != null
            ? writeTransaction.getCommitTimestamp()
            : txManager.getCommitTimestamp();
      } catch (SpannerException e) {
        // ignore
      }
    }
    return null;
  }

  @Override
  public void executeDdl(final ParsedStatement ddl) {
    Preconditions.checkNotNull(ddl);
    Preconditions.checkArgument(
        ddl.getType() == StatementType.DDL, "Statement is not a ddl statement");
    ConnectionPreconditions.checkState(
        !isReadOnly(), "DDL statements are not allowed in read-only mode");
    checkAndMarkUsed();

    try {
      Callable<Void> callable =
          new Callable<Void>() {
            @Override
            public Void call() throws Exception {
              OperationFuture<Void, UpdateDatabaseDdlMetadata> operation =
                  ddlClient.executeDdl(ddl.getSqlWithoutComments());
              return operation.get();
            }
          };
      executeStatement(ddl, callable, null);
      state = UnitOfWorkState.COMMITTED;
    } catch (Throwable e) {
      state = UnitOfWorkState.COMMIT_FAILED;
      throw e;
    }
  }

  @Override
  public long executeUpdate(ParsedStatement update) {
    return get(executeUpdateAsync(update));
  }

  @Override
  public ApiFuture<Long> executeUpdateAsync(ParsedStatement update) {
    Preconditions.checkNotNull(update);
    Preconditions.checkArgument(update.isUpdate(), "Statement is not an update statement");
    ConnectionPreconditions.checkState(
        !isReadOnly(), "Update statements are not allowed in read-only mode");
    checkAndMarkUsed();

    ApiFuture<Long> res;
    switch (autocommitDmlMode) {
      case TRANSACTIONAL:
        res = executeTransactionalUpdateAsync(update);
        break;
      case PARTITIONED_NON_ATOMIC:
        res = executePartitionedUpdateAsync(update);
        break;
      default:
        throw SpannerExceptionFactory.newSpannerException(
            ErrorCode.FAILED_PRECONDITION, "Unknown dml mode: " + autocommitDmlMode);
    }
    ApiFutures.addCallback(
        res,
        new ApiFutureCallback<Long>() {
          @Override
          public void onFailure(Throwable t) {
            state = UnitOfWorkState.COMMIT_FAILED;
          }

          @Override
          public void onSuccess(Long result) {
            state = UnitOfWorkState.COMMITTED;
          }
        },
        MoreExecutors.directExecutor());
    return res;
  }

  private final ParsedStatement executeBatchUpdateStatement =
      StatementParser.INSTANCE.parse(Statement.of("RUN BATCH"));

  @Override
  public long[] executeBatchUpdate(Iterable<ParsedStatement> updates) {
    Preconditions.checkNotNull(updates);
    for (ParsedStatement update : updates) {
      Preconditions.checkArgument(
          update.isUpdate(),
          "Statement is not an update statement: " + update.getSqlWithoutComments());
    }
    ConnectionPreconditions.checkState(
        !isReadOnly(), "Batch update statements are not allowed in read-only mode");
    checkAndMarkUsed();

    long[] res;
    try {
      switch (autocommitDmlMode) {
        case TRANSACTIONAL:
          res =
              executeAsyncTransactionalUpdate(
                  executeBatchUpdateStatement, new TransactionalBatchUpdateCallable(updates));
          break;
        case PARTITIONED_NON_ATOMIC:
          throw SpannerExceptionFactory.newSpannerException(
              ErrorCode.FAILED_PRECONDITION,
              "Batch updates are not allowed in " + autocommitDmlMode);
        default:
          throw SpannerExceptionFactory.newSpannerException(
              ErrorCode.FAILED_PRECONDITION, "Unknown dml mode: " + autocommitDmlMode);
      }
    } catch (SpannerBatchUpdateException e) {
      // Batch update exceptions does not cause a rollback.
      state = UnitOfWorkState.COMMITTED;
      throw e;
    } catch (Throwable e) {
      state = UnitOfWorkState.COMMIT_FAILED;
      throw e;
    }
    state = UnitOfWorkState.COMMITTED;
    return res;
  }

  @Override
  public ApiFuture<long[]> executeBatchUpdateAsync(Iterable<ParsedStatement> updates) {
    Preconditions.checkNotNull(updates);
    for (ParsedStatement update : updates) {
      Preconditions.checkArgument(
          update.isUpdate(),
          "Statement is not an update statement: " + update.getSqlWithoutComments());
    }
    ConnectionPreconditions.checkState(
        !isReadOnly(), "Batch update statements are not allowed in read-only mode");
    checkAndMarkUsed();

    ApiFuture<long[]> res;
    switch (autocommitDmlMode) {
      case TRANSACTIONAL:
        res = executeTransactionalBatchUpdateAsync(updates);
        break;
      case PARTITIONED_NON_ATOMIC:
        throw SpannerExceptionFactory.newSpannerException(
            ErrorCode.FAILED_PRECONDITION, "Batch updates are not allowed in " + autocommitDmlMode);
      default:
        throw SpannerExceptionFactory.newSpannerException(
            ErrorCode.FAILED_PRECONDITION, "Unknown dml mode: " + autocommitDmlMode);
    }
    ApiFutures.addCallback(
        res,
        new ApiFutureCallback<long[]>() {
          @Override
          public void onFailure(Throwable t) {
            if (t instanceof SpannerBatchUpdateException) {
              // Batch update exceptions does not cause a rollback.
              state = UnitOfWorkState.COMMITTED;
            } else {
              state = UnitOfWorkState.COMMIT_FAILED;
            }
          }

          @Override
          public void onSuccess(long[] result) {
            state = UnitOfWorkState.COMMITTED;
          }
        },
        MoreExecutors.directExecutor());
    return res;
  }

  /** Base class for executing DML updates (both single statements and batches). */
  private abstract class AbstractUpdateCallable<T> implements Callable<T> {
    abstract T executeUpdate(TransactionContext txContext);

    @Override
    public T call() throws Exception {
      try {
        txManager = dbClient.transactionManager();
        // Check the interrupted state after each (possible) round-trip to the db to allow the
        // statement to be cancelled.
        checkInterrupted();
        try (TransactionContext txContext = txManager.begin()) {
          checkInterrupted();
          T res = executeUpdate(txContext);
          checkInterrupted();
          txManager.commit();
          checkInterrupted();
          return res;
        }
      } finally {
        if (txManager != null) {
          // Calling txManager.close() will rollback the transaction if it is still active, i.e. if
          // an error occurred before the commit() call returned successfully.
          txManager.close();
        }
      }
    }
  }

  /** {@link Callable} for a batch update. */
  private final class TransactionalBatchUpdateCallable extends AbstractUpdateCallable<long[]> {
    private final List<Statement> updates;

    private TransactionalBatchUpdateCallable(Iterable<ParsedStatement> updates) {
      this.updates = new LinkedList<>();
      for (ParsedStatement update : updates) {
        this.updates.add(update.getStatement());
      }
    }

    @Override
    long[] executeUpdate(TransactionContext txContext) {
      return txContext.batchUpdate(updates);
    }
  }

  private ApiFuture<Long> executeTransactionalUpdateAsync(final ParsedStatement update) {
    Callable<Long> callable =
        new Callable<Long>() {
          @Override
          public Long call() throws Exception {
            writeTransaction = dbClient.readWriteTransaction();
            return writeTransaction.run(
                new TransactionCallable<Long>() {
                  @Override
                  public Long run(TransactionContext transaction) throws Exception {
                    return transaction.executeUpdate(update.getStatement());
                  }
                });
          }
        };
    return executeStatementAsync(
        update,
        callable,
        ImmutableList.<MethodDescriptor<?, ?>>of(
            SpannerGrpc.getExecuteSqlMethod(), SpannerGrpc.getCommitMethod()));
  }

  private ApiFuture<Long> executePartitionedUpdateAsync(final ParsedStatement update) {
    Callable<Long> callable =
        new Callable<Long>() {
          @Override
          public Long call() throws Exception {
            return dbClient.executePartitionedUpdate(update.getStatement());
          }
        };
    return executeStatementAsync(update, callable, SpannerGrpc.getExecuteStreamingSqlMethod());
  }

  private ApiFuture<long[]> executeTransactionalBatchUpdateAsync(
      final Iterable<ParsedStatement> updates) {
    Callable<long[]> callable =
        new Callable<long[]>() {
          @Override
          public long[] call() throws Exception {
            writeTransaction = dbClient.readWriteTransaction();
            return writeTransaction.run(
                new TransactionCallable<long[]>() {
                  @Override
                  public long[] run(TransactionContext transaction) throws Exception {
                    return transaction.batchUpdate(
                        Iterables.transform(
                            updates,
                            new Function<ParsedStatement, Statement>() {
                              @Override
                              public Statement apply(ParsedStatement input) {
                                return input.getStatement();
                              }
                            }));
                  }
                });
          }
        };
    return executeStatementAsync(
        executeBatchUpdateStatement, callable, SpannerGrpc.getExecuteBatchDmlMethod());
  }

  private <T> T executeAsyncTransactionalUpdate(
      final ParsedStatement update, final AbstractUpdateCallable<T> callable) {
    long startedTime = System.currentTimeMillis();
    // This method uses a TransactionManager instead of the TransactionRunner in order to be able to
    // handle timeouts and canceling of a statement.
    while (true) {
      try {
        return executeStatement(update, callable, SpannerGrpc.getExecuteSqlMethod());
      } catch (AbortedException e) {
        try {
          Thread.sleep(e.getRetryDelayInMillis() / 1000);
        } catch (InterruptedException e1) {
          throw SpannerExceptionFactory.newSpannerException(
              ErrorCode.CANCELLED, "Statement execution was interrupted", e1);
        }
        // Check whether the timeout time has been exceeded.
        long executionTime = System.currentTimeMillis() - startedTime;
        if (getStatementTimeout().hasTimeout()
            && executionTime > getStatementTimeout().getTimeoutValue(TimeUnit.MILLISECONDS)) {
          throw SpannerExceptionFactory.newSpannerException(
              ErrorCode.DEADLINE_EXCEEDED,
              "Statement execution timeout occurred for " + update.getSqlWithoutComments());
        }
      }
    }
  }

  private void checkInterrupted() throws InterruptedException {
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedException();
    }
  }

  @Override
  public void write(Iterable<Mutation> mutations) {
    get(writeAsync(mutations));
  }

  private final ParsedStatement commitStatement =
      StatementParser.INSTANCE.parse(Statement.of("COMMIT"));

  @Override
  public ApiFuture<Void> writeAsync(final Iterable<Mutation> mutations) {
    Preconditions.checkNotNull(mutations);
    ConnectionPreconditions.checkState(
        !isReadOnly(), "Update statements are not allowed in read-only mode");
    checkAndMarkUsed();

    Callable<Void> callable =
        new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            writeTransaction = dbClient.readWriteTransaction();
            return writeTransaction.run(
                new TransactionCallable<Void>() {
                  @Override
                  public Void run(TransactionContext transaction) throws Exception {
                    transaction.buffer(mutations);
                    return null;
                  }
                });
          }
        };
    ApiFuture<Void> res =
        executeStatementAsync(commitStatement, callable, SpannerGrpc.getCommitMethod());
    ApiFutures.addCallback(
        res,
        new ApiFutureCallback<Void>() {
          @Override
          public void onFailure(Throwable t) {
            state = UnitOfWorkState.COMMIT_FAILED;
          }

          @Override
          public void onSuccess(Void result) {
            state = UnitOfWorkState.COMMITTED;
          }
        },
        MoreExecutors.directExecutor());
    return res;
  }

  @Override
  public void commit() {
    get(commitAsync());
  }

  @Override
  public ApiFuture<Void> commitAsync() {
    throw SpannerExceptionFactory.newSpannerException(
        ErrorCode.FAILED_PRECONDITION, "Commit is not supported for single-use transactions");
  }

  @Override
  public void rollback() {
    get(rollbackAsync());
  }

  @Override
  public ApiFuture<Void> rollbackAsync() {
    throw SpannerExceptionFactory.newSpannerException(
        ErrorCode.FAILED_PRECONDITION, "Rollback is not supported for single-use transactions");
  }

  @Override
  public long[] runBatch() {
    throw SpannerExceptionFactory.newSpannerException(
        ErrorCode.FAILED_PRECONDITION, "Run batch is not supported for single-use transactions");
  }

  @Override
  public void abortBatch() {
    throw SpannerExceptionFactory.newSpannerException(
        ErrorCode.FAILED_PRECONDITION, "Run batch is not supported for single-use transactions");
  }
}
