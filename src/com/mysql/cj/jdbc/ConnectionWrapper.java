/*
  Copyright (c) 2002, 2015, Oracle and/or its affiliates. All rights reserved.

  The MySQL Connector/J is licensed under the terms of the GPLv2
  <http://www.gnu.org/licenses/old-licenses/gpl-2.0.html>, like most MySQL Connectors.
  There are special exceptions to the terms and conditions of the GPLv2 as it is applied to
  this software, see the FLOSS License Exception
  <http://www.mysql.com/about/legal/licensing/foss-exception.html>.

  This program is free software; you can redistribute it and/or modify it under the terms
  of the GNU General Public License as published by the Free Software Foundation; version 2
  of the License.

  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  See the GNU General Public License for more details.

  You should have received a copy of the GNU General Public License along with this
  program; if not, write to the Free Software Foundation, Inc., 51 Franklin St, Fifth
  Floor, Boston, MA 02110-1301  USA

 */

package com.mysql.cj.jdbc;

import java.lang.reflect.Proxy;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Timer;
import java.util.concurrent.Executor;

import com.mysql.cj.api.Extension;
import com.mysql.cj.api.ProfilerEventHandler;
import com.mysql.cj.api.exceptions.ExceptionInterceptor;
import com.mysql.cj.api.jdbc.JdbcConnection;
import com.mysql.cj.api.jdbc.JdbcPropertySet;
import com.mysql.cj.api.jdbc.ResultSetInternalMethods;
import com.mysql.cj.api.jdbc.interceptors.StatementInterceptorV2;
import com.mysql.cj.api.log.Log;
import com.mysql.cj.core.Messages;
import com.mysql.cj.core.ServerVersion;
import com.mysql.cj.core.conf.PropertyDefinitions;
import com.mysql.cj.core.exceptions.ConnectionIsClosedException;
import com.mysql.cj.core.exceptions.ExceptionFactory;
import com.mysql.cj.core.exceptions.MysqlErrorNumbers;
import com.mysql.cj.jdbc.exceptions.SQLError;
import com.mysql.cj.mysqla.MysqlaSession;
import com.mysql.cj.mysqla.io.Buffer;

/**
 * This class serves as a wrapper for the org.gjt.mm.mysql.jdbc2.Connection class. It is returned to the application server which may wrap it again and then
 * return it to the application client in response to dataSource.getConnection().
 * 
 * All method invocations are forwarded to org.gjt.mm.mysql.jdbc2.Connection unless the close method was previously called, in which case a sqlException is
 * thrown. The close method performs a 'logical close' on the connection.
 * 
 * All sqlExceptions thrown by the physical connection are intercepted and sent to connectionEvent listeners before being thrown to client.
 */
public class ConnectionWrapper extends WrapperBase implements JdbcConnection {
    protected JdbcConnection mc = null;

    private String invalidHandleStr = "Logical handle no longer valid";

    private boolean closed;

    private boolean isForXa;

    protected static ConnectionWrapper getInstance(MysqlPooledConnection mysqlPooledConnection, JdbcConnection mysqlConnection, boolean forXa)
            throws SQLException {
        return new ConnectionWrapper(mysqlPooledConnection, mysqlConnection, forXa);
    }

    /**
     * Construct a new LogicalHandle and set instance variables
     * 
     * @param mysqlPooledConnection
     *            reference to object that instantiated this object
     * @param mysqlConnection
     *            physical connection to db
     * 
     * @throws SQLException
     *             if an error occurs.
     */
    public ConnectionWrapper(MysqlPooledConnection mysqlPooledConnection, JdbcConnection mysqlConnection, boolean forXa) throws SQLException {
        super(mysqlPooledConnection);

        this.mc = mysqlConnection;
        this.closed = false;
        this.isForXa = forXa;

        if (this.isForXa) {
            setInGlobalTx(false);
        }
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @see java.sql.Connection#setAutoCommit
     */
    public void setAutoCommit(boolean autoCommit) throws SQLException {

        if (autoCommit && isInGlobalTx()) {
            throw SQLError.createSQLException(Messages.getString("ConnectionWrapper.0"), SQLError.SQL_STATE_INVALID_TRANSACTION_TERMINATION,
                    MysqlErrorNumbers.ER_XA_RMERR, this.exceptionInterceptor);
        }

        try {
            this.mc.setAutoCommit(autoCommit);
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @see java.sql.Connection#getAutoCommit()
     */
    public boolean getAutoCommit() throws SQLException {

        try {
            return this.mc.getAutoCommit();
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return false; // we don't reach this code, compiler can't tell
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @see java.sql.Connection#setCatalog()
     */
    public void setCatalog(String catalog) throws SQLException {

        try {
            this.mc.setCatalog(catalog);
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @return the current catalog
     * 
     * @throws SQLException
     *             if an error occurs
     */
    public String getCatalog() throws SQLException {

        try {
            return this.mc.getCatalog();
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // we don't reach this code, compiler can't tell
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @see java.sql.Connection#isClosed()
     */
    public boolean isClosed() throws SQLException {
        return (this.closed || this.mc.isClosed());
    }

    public boolean isMasterConnection() {
        return this.mc.isMasterConnection();
    }

    /**
     * @see JdbcConnection#setHoldability(int)
     */
    public void setHoldability(int arg0) throws SQLException {

        try {
            this.mc.setHoldability(arg0);
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }
    }

    /**
     * @see JdbcConnection#getHoldability()
     */
    public int getHoldability() throws SQLException {

        try {
            return this.mc.getHoldability();
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return Statement.CLOSE_CURRENT_RESULT; // we don't reach this code,
        // compiler can't tell
    }

    /**
     * Allows clients to determine how long this connection has been idle.
     * 
     * @return how long the connection has been idle.
     */
    public long getIdleFor() {
        return this.mc.getIdleFor();
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @return a metadata instance
     * 
     * @throws SQLException
     *             if an error occurs
     */
    public java.sql.DatabaseMetaData getMetaData() throws SQLException {

        try {
            return this.mc.getMetaData();
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // we don't reach this code, compiler can't tell
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @see java.sql.Connection#setReadOnly()
     */
    public void setReadOnly(boolean readOnly) throws SQLException {

        try {
            this.mc.setReadOnly(readOnly);
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @see java.sql.Connection#isReadOnly()
     */
    public boolean isReadOnly() throws SQLException {

        try {
            return this.mc.isReadOnly();
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return false; // we don't reach this code, compiler can't tell
    }

    /**
     * @see JdbcConnection#setSavepoint()
     */
    public java.sql.Savepoint setSavepoint() throws SQLException {

        if (isInGlobalTx()) {
            throw SQLError.createSQLException(Messages.getString("ConnectionWrapper.0"), SQLError.SQL_STATE_INVALID_TRANSACTION_TERMINATION,
                    MysqlErrorNumbers.ER_XA_RMERR, this.exceptionInterceptor);
        }

        try {
            return this.mc.setSavepoint();
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // we don't reach this code, compiler can't tell
    }

    /**
     * @see JdbcConnection#setSavepoint(String)
     */
    public java.sql.Savepoint setSavepoint(String arg0) throws SQLException {

        if (isInGlobalTx()) {
            throw SQLError.createSQLException(Messages.getString("ConnectionWrapper.0"), SQLError.SQL_STATE_INVALID_TRANSACTION_TERMINATION,
                    MysqlErrorNumbers.ER_XA_RMERR, this.exceptionInterceptor);
        }

        try {
            return this.mc.setSavepoint(arg0);
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // we don't reach this code, compiler can't tell
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @see java.sql.Connection#setTransactionIsolation()
     */
    public void setTransactionIsolation(int level) throws SQLException {

        try {
            this.mc.setTransactionIsolation(level);
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @see java.sql.Connection#getTransactionIsolation()
     */
    public int getTransactionIsolation() throws SQLException {

        try {
            return this.mc.getTransactionIsolation();
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return TRANSACTION_REPEATABLE_READ; // we don't reach this code,
        // compiler can't tell
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @see java.sql.Connection#getTypeMap()
     */
    public java.util.Map<String, Class<?>> getTypeMap() throws SQLException {

        try {
            return this.mc.getTypeMap();
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // we don't reach this code, compiler can't tell
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @see java.sql.Connection#getWarnings
     */
    public java.sql.SQLWarning getWarnings() throws SQLException {

        try {
            return this.mc.getWarnings();
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // we don't reach this code, compiler can't tell
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @throws SQLException
     *             if an error occurs
     */
    public void clearWarnings() throws SQLException {

        try {
            this.mc.clearWarnings();
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }
    }

    /**
     * The physical connection is not actually closed. the physical connection
     * is closed when the application server calls
     * mysqlPooledConnection.close(). this object is de-referenced by the pooled
     * connection each time mysqlPooledConnection.getConnection() is called by
     * app server.
     * 
     * @throws SQLException
     *             if an error occurs
     */
    public void close() throws SQLException {
        try {
            close(true);
        } finally {
            this.unwrappedInterfaces = null;
        }
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @throws SQLException
     *             if an error occurs
     */
    public void commit() throws SQLException {

        if (isInGlobalTx()) {
            throw SQLError.createSQLException(Messages.getString("ConnectionWrapper.1"), SQLError.SQL_STATE_INVALID_TRANSACTION_TERMINATION,
                    MysqlErrorNumbers.ER_XA_RMERR, this.exceptionInterceptor);
        }

        try {
            this.mc.commit();
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @see java.sql.Connection#createStatement()
     */
    public java.sql.Statement createStatement() throws SQLException {

        try {
            return StatementWrapper.getInstance(this, this.pooledConnection, this.mc.createStatement());
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // we don't reach this code, compiler can't tell
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @see java.sql.Connection#createStatement()
     */
    public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {

        try {
            return StatementWrapper.getInstance(this, this.pooledConnection, this.mc.createStatement(resultSetType, resultSetConcurrency));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // we don't reach this code, compiler can't tell
    }

    /**
     * @see JdbcConnection#createStatement(int, int, int)
     */
    public java.sql.Statement createStatement(int arg0, int arg1, int arg2) throws SQLException {

        try {
            return StatementWrapper.getInstance(this, this.pooledConnection, this.mc.createStatement(arg0, arg1, arg2));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // we don't reach this code, compiler can't tell
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @see java.sql.Connection#nativeSQL()
     */
    public String nativeSQL(String sql) throws SQLException {

        try {
            return this.mc.nativeSQL(sql);
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // we don't reach this code, compiler can't tell
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @see java.sql.Connection#prepareCall()
     */
    public java.sql.CallableStatement prepareCall(String sql) throws SQLException {

        try {
            return CallableStatementWrapper.getInstance(this, this.pooledConnection, this.mc.prepareCall(sql));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // we don't reach this code, compiler can't tell
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @see java.sql.Connection#prepareCall()
     */
    public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {

        try {
            return CallableStatementWrapper.getInstance(this, this.pooledConnection, this.mc.prepareCall(sql, resultSetType, resultSetConcurrency));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // we don't reach this code, compiler can't tell
    }

    /**
     * @see JdbcConnection#prepareCall(String, int, int, int)
     */
    public java.sql.CallableStatement prepareCall(String arg0, int arg1, int arg2, int arg3) throws SQLException {

        try {
            return CallableStatementWrapper.getInstance(this, this.pooledConnection, this.mc.prepareCall(arg0, arg1, arg2, arg3));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // we don't reach this code, compiler can't tell
    }

    public java.sql.PreparedStatement clientPrepare(String sql) throws SQLException {

        try {
            return new PreparedStatementWrapper(this, this.pooledConnection, this.mc.clientPrepareStatement(sql));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null;
    }

    public java.sql.PreparedStatement clientPrepare(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {

        try {
            return new PreparedStatementWrapper(this, this.pooledConnection, this.mc.clientPrepareStatement(sql, resultSetType, resultSetConcurrency));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null;
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @see java.sql.Connection#prepareStatement()
     */
    public java.sql.PreparedStatement prepareStatement(String sql) throws SQLException {

        try {
            return PreparedStatementWrapper.getInstance(this, this.pooledConnection, this.mc.prepareStatement(sql));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // we don't reach this code, compiler can't tell
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @see java.sql.Connection#prepareStatement()
     */
    public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {

        try {
            return PreparedStatementWrapper.getInstance(this, this.pooledConnection, this.mc.prepareStatement(sql, resultSetType, resultSetConcurrency));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // we don't reach this code, compiler can't tell
    }

    /**
     * @see JdbcConnection#prepareStatement(String, int, int, int)
     */
    public java.sql.PreparedStatement prepareStatement(String arg0, int arg1, int arg2, int arg3) throws SQLException {

        try {
            return PreparedStatementWrapper.getInstance(this, this.pooledConnection, this.mc.prepareStatement(arg0, arg1, arg2, arg3));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // we don't reach this code, compiler can't tell
    }

    /**
     * @see JdbcConnection#prepareStatement(String, int)
     */
    public java.sql.PreparedStatement prepareStatement(String arg0, int arg1) throws SQLException {

        try {
            return PreparedStatementWrapper.getInstance(this, this.pooledConnection, this.mc.prepareStatement(arg0, arg1));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // we don't reach this code, compiler can't tell
    }

    /**
     * @see JdbcConnection#prepareStatement(String, int[])
     */
    public java.sql.PreparedStatement prepareStatement(String arg0, int[] arg1) throws SQLException {

        try {
            return PreparedStatementWrapper.getInstance(this, this.pooledConnection, this.mc.prepareStatement(arg0, arg1));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // we don't reach this code, compiler can't tell
    }

    /**
     * @see JdbcConnection#prepareStatement(String, String[])
     */
    public java.sql.PreparedStatement prepareStatement(String arg0, String[] arg1) throws SQLException {

        try {
            return PreparedStatementWrapper.getInstance(this, this.pooledConnection, this.mc.prepareStatement(arg0, arg1));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // we don't reach this code, compiler can't tell
    }

    /**
     * @see JdbcConnection#releaseSavepoint(Savepoint)
     */
    public void releaseSavepoint(Savepoint arg0) throws SQLException {

        try {
            this.mc.releaseSavepoint(arg0);
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }
    }

    /**
     * Passes call to method on physical connection instance. Notifies listeners
     * of any caught exceptions before re-throwing to client.
     * 
     * @see java.sql.Connection#rollback()
     */
    public void rollback() throws SQLException {

        if (isInGlobalTx()) {
            throw SQLError.createSQLException(Messages.getString("ConnectionWrapper.2"), SQLError.SQL_STATE_INVALID_TRANSACTION_TERMINATION,
                    MysqlErrorNumbers.ER_XA_RMERR, this.exceptionInterceptor);
        }

        try {
            this.mc.rollback();
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }
    }

    /**
     * @see JdbcConnection#rollback(Savepoint)
     */
    public void rollback(Savepoint arg0) throws SQLException {

        if (isInGlobalTx()) {
            throw SQLError.createSQLException(Messages.getString("ConnectionWrapper.2"), SQLError.SQL_STATE_INVALID_TRANSACTION_TERMINATION,
                    MysqlErrorNumbers.ER_XA_RMERR, this.exceptionInterceptor);
        }

        try {
            this.mc.rollback(arg0);
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }
    }

    public boolean isSameResource(com.mysql.cj.api.jdbc.JdbcConnection c) {
        if (c instanceof ConnectionWrapper) {
            return this.mc.isSameResource(((ConnectionWrapper) c).mc);
        }
        return this.mc.isSameResource(c);
    }

    protected void close(boolean fireClosedEvent) throws SQLException {
        synchronized (this.pooledConnection) {
            if (this.closed) {
                return;
            }

            if (!isInGlobalTx() && this.mc.getPropertySet().getBooleanReadableProperty(PropertyDefinitions.PNAME_rollbackOnPooledClose).getValue()
                    && !this.getAutoCommit()) {
                rollback();
            }

            if (fireClosedEvent) {
                this.pooledConnection.callConnectionEventListeners(MysqlPooledConnection.CONNECTION_CLOSED_EVENT, null);
            }

            // set closed status to true so that if application client tries to make additional calls a sqlException will be thrown. The physical connection is
            // re-used by the pooled connection each time getConnection is called.
            this.closed = true;
        }
    }

    public void checkClosed() {
        if (this.closed) {
            throw ExceptionFactory.createException(ConnectionIsClosedException.class, this.invalidHandleStr, this.exceptionInterceptor);
        }
    }

    public boolean isInGlobalTx() {
        return this.mc.isInGlobalTx();
    }

    public void setInGlobalTx(boolean flag) {
        this.mc.setInGlobalTx(flag);
    }

    public void ping() throws SQLException {
        if (this.mc != null) {
            this.mc.ping();
        }
    }

    public void changeUser(String userName, String newPassword) throws SQLException {

        try {
            this.mc.changeUser(userName, newPassword);
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }
    }

    public void clearHasTriedMaster() {
        this.mc.clearHasTriedMaster();
    }

    public java.sql.PreparedStatement clientPrepareStatement(String sql) throws SQLException {

        try {
            return PreparedStatementWrapper.getInstance(this, this.pooledConnection, this.mc.clientPrepareStatement(sql));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null;
    }

    public java.sql.PreparedStatement clientPrepareStatement(String sql, int autoGenKeyIndex) throws SQLException {
        try {
            return PreparedStatementWrapper.getInstance(this, this.pooledConnection, this.mc.clientPrepareStatement(sql, autoGenKeyIndex));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null;
    }

    public java.sql.PreparedStatement clientPrepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        try {
            return PreparedStatementWrapper.getInstance(this, this.pooledConnection, this.mc.clientPrepareStatement(sql, resultSetType, resultSetConcurrency));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null;
    }

    public java.sql.PreparedStatement clientPrepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        try {
            return PreparedStatementWrapper.getInstance(this, this.pooledConnection,
                    this.mc.clientPrepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null;
    }

    public java.sql.PreparedStatement clientPrepareStatement(String sql, int[] autoGenKeyIndexes) throws SQLException {
        try {
            return PreparedStatementWrapper.getInstance(this, this.pooledConnection, this.mc.clientPrepareStatement(sql, autoGenKeyIndexes));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null;
    }

    public java.sql.PreparedStatement clientPrepareStatement(String sql, String[] autoGenKeyColNames) throws SQLException {
        try {
            return PreparedStatementWrapper.getInstance(this, this.pooledConnection, this.mc.clientPrepareStatement(sql, autoGenKeyColNames));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null;
    }

    public int getActiveStatementCount() {
        return this.mc.getActiveStatementCount();
    }

    public Log getLog() {
        return this.mc.getLog();
    }

    /**
     * @deprecated replaced by <code>getServerCharset()</code>
     */
    @Deprecated
    public String getServerCharacterEncoding() {
        return getServerCharset();
    }

    public String getServerCharset() {
        return this.mc.getServerCharset();
    }

    public String getStatementComment() {
        return this.mc.getStatementComment();
    }

    public boolean hasTriedMaster() {
        return this.mc.hasTriedMaster();
    }

    public boolean isAbonormallyLongQuery(long millisOrNanos) {
        return this.mc.isAbonormallyLongQuery(millisOrNanos);
    }

    public boolean isNoBackslashEscapesSet() {
        return this.mc.isNoBackslashEscapesSet();
    }

    public boolean lowerCaseTableNames() {
        return this.mc.lowerCaseTableNames();
    }

    public void reportQueryTime(long millisOrNanos) {
        this.mc.reportQueryTime(millisOrNanos);
    }

    public void resetServerState() throws SQLException {

        try {
            this.mc.resetServerState();
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }
    }

    public java.sql.PreparedStatement serverPrepareStatement(String sql) throws SQLException {

        try {
            return PreparedStatementWrapper.getInstance(this, this.pooledConnection, this.mc.serverPrepareStatement(sql));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null;
    }

    public java.sql.PreparedStatement serverPrepareStatement(String sql, int autoGenKeyIndex) throws SQLException {
        try {
            return PreparedStatementWrapper.getInstance(this, this.pooledConnection, this.mc.serverPrepareStatement(sql, autoGenKeyIndex));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null;
    }

    public java.sql.PreparedStatement serverPrepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        try {
            return PreparedStatementWrapper.getInstance(this, this.pooledConnection, this.mc.serverPrepareStatement(sql, resultSetType, resultSetConcurrency));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null;
    }

    public java.sql.PreparedStatement serverPrepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        try {
            return PreparedStatementWrapper.getInstance(this, this.pooledConnection,
                    this.mc.serverPrepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null;
    }

    public java.sql.PreparedStatement serverPrepareStatement(String sql, int[] autoGenKeyIndexes) throws SQLException {
        try {
            return PreparedStatementWrapper.getInstance(this, this.pooledConnection, this.mc.serverPrepareStatement(sql, autoGenKeyIndexes));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null;
    }

    public java.sql.PreparedStatement serverPrepareStatement(String sql, String[] autoGenKeyColNames) throws SQLException {
        try {
            return PreparedStatementWrapper.getInstance(this, this.pooledConnection, this.mc.serverPrepareStatement(sql, autoGenKeyColNames));
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null;
    }

    public void setFailedOver(boolean flag) {
        this.mc.setFailedOver(flag);

    }

    public void setStatementComment(String comment) {
        this.mc.setStatementComment(comment);

    }

    public void shutdownServer() throws SQLException {

        try {
            this.mc.shutdownServer();
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

    }

    public boolean versionMeetsMinimum(int major, int minor, int subminor) {

        return this.mc.versionMeetsMinimum(major, minor, subminor);
    }

    public void initializeExtension(Extension ex) {
        this.mc.initializeExtension(ex);
    }

    public int getAutoIncrementIncrement() {
        return this.mc.getAutoIncrementIncrement();
    }

    public ExceptionInterceptor getExceptionInterceptor() {
        return this.pooledConnection.getExceptionInterceptor();
    }

    public boolean hasSameProperties(JdbcConnection c) {
        return this.mc.hasSameProperties(c);
    }

    public Properties getProperties() {
        return this.mc.getProperties();
    }

    public String getHost() {
        return this.mc.getHost();
    }

    public void setProxy(JdbcConnection conn) {
        this.mc.setProxy(conn);
    }

    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {

        try {
            this.mc.setTypeMap(map);
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }
    }

    public boolean isServerLocal() throws SQLException {
        return this.mc.isServerLocal();
    }

    public void setSchema(String schema) throws SQLException {
        this.mc.setSchema(schema);
    }

    public String getSchema() throws SQLException {
        return this.mc.getSchema();
    }

    public void abort(Executor executor) throws SQLException {
        this.mc.abort(executor);
    }

    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        this.mc.setNetworkTimeout(executor, milliseconds);
    }

    public int getNetworkTimeout() throws SQLException {
        return this.mc.getNetworkTimeout();
    }

    public void abortInternal() throws SQLException {
        this.mc.abortInternal();
    }

    public Object getConnectionMutex() {
        return this.mc.getConnectionMutex();
    }

    public int getSessionMaxRows() {
        return this.mc.getSessionMaxRows();
    }

    public void setSessionMaxRows(int max) throws SQLException {
        this.mc.setSessionMaxRows(max);
    }

    public Clob createClob() throws SQLException {

        try {
            return ((java.sql.Connection) this.mc).createClob();
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // never reached, but compiler can't tell
    }

    public Blob createBlob() throws SQLException {

        try {
            return ((java.sql.Connection) this.mc).createBlob();
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // never reached, but compiler can't tell
    }

    public NClob createNClob() throws SQLException {

        try {
            return ((java.sql.Connection) this.mc).createNClob();
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // never reached, but compiler can't tell
    }

    public SQLXML createSQLXML() throws SQLException {

        try {
            return ((java.sql.Connection) this.mc).createSQLXML();
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // never reached, but compiler can't tell
    }

    /**
     * Returns true if the connection has not been closed and is still valid.
     * The driver shall submit a query on the connection or use some other
     * mechanism that positively verifies the connection is still valid when
     * this method is called.
     * <p>
     * The query submitted by the driver to validate the connection shall be executed in the context of the current transaction.
     * 
     * @param timeout
     *            - The time in seconds to wait for the database operation used
     *            to validate the connection to complete. If the timeout period
     *            expires before the operation completes, this method returns
     *            false. A value of 0 indicates a timeout is not applied to the
     *            database operation.
     *            <p>
     * @return true if the connection is valid, false otherwise
     * @exception SQLException
     *                if the value supplied for <code>timeout</code> is less
     *                then 0
     * @since 1.6
     */
    public synchronized boolean isValid(int timeout) throws SQLException {
        try {
            return ((java.sql.Connection) this.mc).isValid(timeout);
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return false; // never reached, but compiler can't tell
    }

    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        try {
            checkClosed();

            ((java.sql.Connection) this.mc).setClientInfo(name, value);
        } catch (SQLException sqlException) {
            try {
                checkAndFireConnectionError(sqlException);
            } catch (SQLException sqlEx2) {
                SQLClientInfoException clientEx = new SQLClientInfoException();
                clientEx.initCause(sqlEx2);

                throw clientEx;
            }
        }
    }

    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        try {
            checkClosed();

            ((java.sql.Connection) this.mc).setClientInfo(properties);
        } catch (SQLException sqlException) {
            try {
                checkAndFireConnectionError(sqlException);
            } catch (SQLException sqlEx2) {
                SQLClientInfoException clientEx = new SQLClientInfoException();
                clientEx.initCause(sqlEx2);

                throw clientEx;
            }
        }
    }

    public String getClientInfo(String name) throws SQLException {

        try {
            return ((java.sql.Connection) this.mc).getClientInfo(name);
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // never reached, but compiler can't tell
    }

    public Properties getClientInfo() throws SQLException {

        try {
            return ((java.sql.Connection) this.mc).getClientInfo();
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // never reached, but compiler can't tell
    }

    public java.sql.Array createArrayOf(String typeName, Object[] elements) throws SQLException {

        try {
            return ((java.sql.Connection) this.mc).createArrayOf(typeName, elements);
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // never reached, but compiler can't tell
    }

    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {

        try {
            return ((java.sql.Connection) this.mc).createStruct(typeName, attributes);
        } catch (SQLException sqlException) {
            checkAndFireConnectionError(sqlException);
        }

        return null; // never reached, but compiler can't tell
    }

    /**
     * Returns an object that implements the given interface to allow access to
     * non-standard methods, or standard methods not exposed by the proxy. The
     * result may be either the object found to implement the interface or a
     * proxy for that object. If the receiver implements the interface then that
     * is the object. If the receiver is a wrapper and the wrapped object
     * implements the interface then that is the object. Otherwise the object is
     * the result of calling <code>unwrap</code> recursively on the wrapped
     * object. If the receiver is not a wrapper and does not implement the
     * interface, then an <code>SQLException</code> is thrown.
     * 
     * @param iface
     *            A Class defining an interface that the result must implement.
     * @return an object that implements the interface. May be a proxy for the
     *         actual implementing object.
     * @throws java.sql.SQLException
     *             If no object found that implements the interface
     * @since 1.6
     */
    public synchronized <T> T unwrap(java.lang.Class<T> iface) throws java.sql.SQLException {
        try {
            if ("java.sql.Connection".equals(iface.getName()) || "java.sql.Wrapper.class".equals(iface.getName())) {
                return iface.cast(this);
            }

            if (this.unwrappedInterfaces == null) {
                this.unwrappedInterfaces = new HashMap();
            }

            Object cachedUnwrapped = this.unwrappedInterfaces.get(iface);

            if (cachedUnwrapped == null) {
                cachedUnwrapped = Proxy.newProxyInstance(this.mc.getClass().getClassLoader(), new Class[] { iface },
                        new ConnectionErrorFiringInvocationHandler(this.mc));
                this.unwrappedInterfaces.put(iface, cachedUnwrapped);
            }

            return iface.cast(cachedUnwrapped);
        } catch (ClassCastException cce) {
            throw SQLError.createSQLException(Messages.getString("Common.UnableToUnwrap", new Object[] { iface.toString() }),
                    SQLError.SQL_STATE_ILLEGAL_ARGUMENT, this.exceptionInterceptor);
        }
    }

    /**
     * Returns true if this either implements the interface argument or is
     * directly or indirectly a wrapper for an object that does. Returns false
     * otherwise. If this implements the interface then return true, else if
     * this is a wrapper then return the result of recursively calling <code>isWrapperFor</code> on the wrapped object. If this does not
     * implement the interface and is not a wrapper, return false. This method
     * should be implemented as a low-cost operation compared to <code>unwrap</code> so that callers can use this method to avoid
     * expensive <code>unwrap</code> calls that may fail. If this method returns
     * true then calling <code>unwrap</code> with the same argument should
     * succeed.
     * 
     * @param interfaces
     *            a Class defining an interface.
     * @return true if this implements the interface or directly or indirectly
     *         wraps an object that does.
     * @throws java.sql.SQLException
     *             if an error occurs while determining whether this is a
     *             wrapper for an object with the given interface.
     * @since 1.6
     */
    public boolean isWrapperFor(Class<?> iface) throws SQLException {

        boolean isInstance = iface.isInstance(this);

        if (isInstance) {
            return true;
        }

        return (iface.getName().equals(JdbcConnection.class.getName()) || iface.getName().equals(JdbcConnection.class.getName()));
    }

    @Override
    public String getProcessHost() {
        return this.mc.getProcessHost();
    }

    @Override
    public MysqlaSession getSession() {
        return this.mc.getSession();
    }

    @Override
    public ProfilerEventHandler getProfilerEventHandlerInstance() {
        return this.mc.getProfilerEventHandlerInstance();
    }

    @Override
    public void setProfilerEventHandlerInstance(ProfilerEventHandler h) {
        this.mc.setProfilerEventHandlerInstance(h);
    }

    @Override
    public long getId() {
        return this.mc.getId();
    }

    @Override
    public String getURL() {
        return this.mc.getURL();
    }

    @Override
    public String getUser() {
        return this.mc.getUser();
    }

    @Override
    public String getEncodingForIndex(int collationIndex) {
        return this.mc.getEncodingForIndex(collationIndex);
    }

    @Override
    public String getErrorMessageEncoding() {
        return this.mc.getErrorMessageEncoding();
    }

    @Override
    public int getMaxBytesPerChar(String javaCharsetName) {
        return this.mc.getMaxBytesPerChar(javaCharsetName);
    }

    @Override
    public int getMaxBytesPerChar(Integer charsetIndex, String javaCharsetName) {
        return this.mc.getMaxBytesPerChar(charsetIndex, javaCharsetName);
    }

    @Override
    public void createNewIO(boolean isForReconnect) {
        this.mc.createNewIO(isForReconnect);
    }

    @Override
    public boolean isProxySet() {
        return this.mc.isProxySet();
    }

    @Override
    public JdbcConnection duplicate() throws SQLException {
        return this.mc.duplicate();
    }

    @Override
    public ResultSetInternalMethods execSQL(StatementImpl callingStatement, String sql, int maxRows, Buffer packet, int resultSetType,
            int resultSetConcurrency, boolean streamResults, String catalog, Field[] cachedMetadata) throws SQLException {
        return this.mc.execSQL(callingStatement, sql, maxRows, packet, resultSetType, resultSetConcurrency, streamResults, catalog, cachedMetadata);
    }

    @Override
    public ResultSetInternalMethods execSQL(StatementImpl callingStatement, String sql, int maxRows, Buffer packet, int resultSetType,
            int resultSetConcurrency, boolean streamResults, String catalog, Field[] cachedMetadata, boolean isBatch) throws SQLException {
        return this.mc.execSQL(callingStatement, sql, maxRows, packet, resultSetType, resultSetConcurrency, streamResults, catalog, cachedMetadata, isBatch);
    }

    @Override
    public JdbcPropertySet getPropertySet() {
        return this.mc.getPropertySet();
    }

    @Override
    public StringBuilder generateConnectionCommentBlock(StringBuilder buf) {
        return this.mc.generateConnectionCommentBlock(buf);
    }

    @Override
    public CachedResultSetMetaData getCachedMetaData(String sql) {
        return this.mc.getCachedMetaData(sql);
    }

    @Override
    public Timer getCancelTimer() {
        return this.mc.getCancelTimer();
    }

    @Override
    public String getCharacterSetMetadata() {
        return this.mc.getCharacterSetMetadata();
    }

    @Override
    public Statement getMetadataSafeStatement() throws SQLException {
        return this.mc.getMetadataSafeStatement();
    }

    @Override
    public boolean getRequiresEscapingEncoder() {
        return this.mc.getRequiresEscapingEncoder();
    }

    @Override
    public ServerVersion getServerVersion() {
        return this.mc.getServerVersion();
    }

    @Override
    public List<StatementInterceptorV2> getStatementInterceptorsInstances() {
        return this.mc.getStatementInterceptorsInstances();
    }

    @Override
    public void incrementNumberOfPreparedExecutes() {
        this.mc.incrementNumberOfPreparedExecutes();
    }

    @Override
    public void incrementNumberOfPrepares() {
        this.mc.incrementNumberOfPrepares();
    }

    @Override
    public void incrementNumberOfResultSetsCreated() {
        this.mc.incrementNumberOfResultSetsCreated();
    }

    @Override
    public void initializeResultsMetadataFromCache(String sql, CachedResultSetMetaData cachedMetaData, ResultSetInternalMethods resultSet) throws SQLException {
        this.mc.initializeResultsMetadataFromCache(sql, cachedMetaData, resultSet);
    }

    @Override
    public void initializeSafeStatementInterceptors() throws SQLException {
        this.mc.initializeSafeStatementInterceptors();
    }

    @Override
    public boolean isReadInfoMsgEnabled() {
        return this.mc.isReadInfoMsgEnabled();
    }

    @Override
    public boolean isReadOnly(boolean useSessionStatus) throws SQLException {
        return this.mc.isReadOnly(useSessionStatus);
    }

    @Override
    public void pingInternal(boolean checkForClosedConnection, int timeoutMillis) throws SQLException {
        this.mc.pingInternal(checkForClosedConnection, timeoutMillis);
    }

    @Override
    public void realClose(boolean calledExplicitly, boolean issueRollback, boolean skipLocalTeardown, Throwable reason) throws SQLException {
        this.mc.realClose(calledExplicitly, issueRollback, skipLocalTeardown, reason);
    }

    @Override
    public void recachePreparedStatement(ServerPreparedStatement pstmt) throws SQLException {
        this.mc.recachePreparedStatement(pstmt);
    }

    @Override
    public void decachePreparedStatement(ServerPreparedStatement pstmt) throws SQLException {
        this.mc.decachePreparedStatement(pstmt);
    }

    @Override
    public void registerQueryExecutionTime(long queryTimeMs) {
        this.mc.registerQueryExecutionTime(queryTimeMs);
    }

    @Override
    public void registerStatement(com.mysql.cj.api.jdbc.Statement stmt) {
        this.mc.registerStatement(stmt);
    }

    @Override
    public void reportNumberOfTablesAccessed(int numTablesAccessed) {
        this.mc.reportNumberOfTablesAccessed(numTablesAccessed);
    }

    @Override
    public void setReadInfoMsgEnabled(boolean flag) {
        this.mc.setReadInfoMsgEnabled(flag);
    }

    @Override
    public void setReadOnlyInternal(boolean readOnlyFlag) throws SQLException {
        this.mc.setReadOnlyInternal(readOnlyFlag);
    }

    @Override
    public boolean storesLowerCaseTableName() {
        return this.mc.storesLowerCaseTableName();
    }

    @Override
    public void throwConnectionClosedException() throws SQLException {
        this.mc.throwConnectionClosedException();
    }

    @Override
    public void transactionBegun() throws SQLException {
        this.mc.transactionBegun();
    }

    @Override
    public void transactionCompleted() throws SQLException {
        this.mc.transactionCompleted();
    }

    @Override
    public void unregisterStatement(com.mysql.cj.api.jdbc.Statement stmt) {
        this.mc.unregisterStatement(stmt);
    }

    @Override
    public void unSafeStatementInterceptors() throws SQLException {
        this.mc.unSafeStatementInterceptors();
    }

    @Override
    public boolean useAnsiQuotedIdentifiers() {
        return this.mc.useAnsiQuotedIdentifiers();
    }

    @Override
    public JdbcConnection getMultiHostSafeProxy() {
        return this.mc.getMultiHostSafeProxy();
    }

}
