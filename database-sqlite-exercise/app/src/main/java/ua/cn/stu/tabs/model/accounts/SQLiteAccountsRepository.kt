package ua.cn.stu.tabs.model.accounts

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.contentValuesOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ua.cn.stu.tabs.model.AccountAlreadyExistsException
import ua.cn.stu.tabs.model.AuthException
import ua.cn.stu.tabs.model.EmptyFieldException
import ua.cn.stu.tabs.model.Field
import ua.cn.stu.tabs.model.accounts.entities.Account
import ua.cn.stu.tabs.model.accounts.entities.SignUpData
import ua.cn.stu.tabs.model.settings.AppSettings
import ua.cn.stu.tabs.model.sqlite.AppSQLiteContract
import ua.cn.stu.tabs.model.sqlite.wrapSQLiteException
import ua.cn.stu.tabs.utils.AsyncLoader

class SQLiteAccountsRepository(
    private val db: SQLiteDatabase,
    private val appSettings: AppSettings,
    private val ioDispatcher: CoroutineDispatcher
) : AccountsRepository {

    private val currentAccountIdFlow = AsyncLoader {
        MutableStateFlow(AccountId(appSettings.getCurrentAccountId()))
    }

    override suspend fun isSignedIn(): Boolean {
        delay(2000)
        return appSettings.getCurrentAccountId() != AppSettings.NO_ACCOUNT_ID
    }

    override suspend fun signIn(email: String, password: String) =
        wrapSQLiteException(ioDispatcher) {
            if (email.isBlank()) throw EmptyFieldException(Field.Email)
            if (password.isBlank()) throw EmptyFieldException(Field.Password)

            delay(1000)

            val accountId = findAccountIdByEmailAndPassword(email, password)
            appSettings.setCurrentAccountId(accountId)
            currentAccountIdFlow.get().value = AccountId(accountId)

            return@wrapSQLiteException
        }

    override suspend fun signUp(signUpData: SignUpData) = wrapSQLiteException(ioDispatcher) {
        signUpData.validate()
        delay(1000)
        createAccount(signUpData)
    }

    override suspend fun logout() {
        appSettings.setCurrentAccountId(AppSettings.NO_ACCOUNT_ID)
        currentAccountIdFlow.get().value = AccountId(AppSettings.NO_ACCOUNT_ID)
    }

    override suspend fun getAccount(): Flow<Account?> {
        return currentAccountIdFlow.get().map { accountId ->
            getAccountById(accountId.value)
        }.flowOn(ioDispatcher)
    }

    override suspend fun updateAccountUsername(newUsername: String) =
        wrapSQLiteException(ioDispatcher) {
            if (newUsername.isBlank()) throw EmptyFieldException(Field.Username)
            delay(1000)
            val accountId = appSettings.getCurrentAccountId()
            if (accountId == AppSettings.NO_ACCOUNT_ID) throw AuthException()

            updateUsernameForAccountId(accountId, newUsername)

            currentAccountIdFlow.get().value = AccountId(accountId)
            return@wrapSQLiteException
        }

    private fun findAccountIdByEmailAndPassword(email: String, password: String): Long {
        val cursor = db.query(
            AppSQLiteContract.AccountsTable.TABLE_NAME,
            arrayOf(
                AppSQLiteContract.AccountsTable.COLUMN_ID,
                AppSQLiteContract.AccountsTable.COLUMN_PASSWORD
            ),
            "${AppSQLiteContract.AccountsTable.COLUMN_EMAIL} = ?",
            arrayOf(email),
            null,
            null,
            null

        )
        return cursor.use {
            if (cursor.count == 0) throw AuthException()
            cursor.moveToFirst()
            val passwordFromDb =
                cursor.getString(cursor.getColumnIndexOrThrow(AppSQLiteContract.AccountsTable.COLUMN_PASSWORD))
            if (passwordFromDb != password) throw  AuthException()

            cursor.getLong(cursor.getColumnIndexOrThrow(AppSQLiteContract.AccountsTable.COLUMN_ID))
        }
    }

    private fun createAccount(signUpData: SignUpData) {
        try {
            db.insertOrThrow(
                AppSQLiteContract.AccountsTable.TABLE_NAME, null, contentValuesOf(
                    AppSQLiteContract.AccountsTable.COLUMN_EMAIL to signUpData.email,
                    AppSQLiteContract.AccountsTable.COLUMN_USERNAME to signUpData.username,
                    AppSQLiteContract.AccountsTable.COLUMN_CREATED_AT to System.currentTimeMillis(),
                    AppSQLiteContract.AccountsTable.COLUMN_EMAIL to signUpData.email,
                )
            )
        } catch (e: SQLiteConstraintException) {
            val appException = AccountAlreadyExistsException()
            appException.initCause(e)
            throw appException
        }
    }

    private fun getAccountById(accountId: Long): Account? {
        val cursor = db.query(
            AppSQLiteContract.AccountsTable.TABLE_NAME,
            arrayOf(
                AppSQLiteContract.AccountsTable.COLUMN_ID,
                AppSQLiteContract.AccountsTable.COLUMN_EMAIL,
                AppSQLiteContract.AccountsTable.COLUMN_USERNAME,
                AppSQLiteContract.AccountsTable.COLUMN_CREATED_AT
            ),
            "${AppSQLiteContract.AccountsTable.COLUMN_ID} = ?",
            arrayOf(accountId.toString()),
            null,
            null,
            null
        )
        return cursor.use {
            if (cursor.count == 0) return@use null
            cursor.moveToFirst()
            Account(
                id = cursor.getLong(cursor.getColumnIndexOrThrow(AppSQLiteContract.AccountsTable.COLUMN_ID)),
                username = cursor.getString(cursor.getColumnIndexOrThrow(AppSQLiteContract.AccountsTable.COLUMN_USERNAME)),
                email = cursor.getString(cursor.getColumnIndexOrThrow(AppSQLiteContract.AccountsTable.COLUMN_EMAIL)),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(AppSQLiteContract.AccountsTable.COLUMN_CREATED_AT))
            )
        }
    }

    private fun updateUsernameForAccountId(accountId: Long, newUsername: String) {
        db.update(
            AppSQLiteContract.AccountsTable.TABLE_NAME,
            contentValuesOf(
                AppSQLiteContract.AccountsTable.COLUMN_USERNAME to newUsername
            ),
            "${AppSQLiteContract.AccountsTable.COLUMN_ID} = ?",
            arrayOf(accountId.toString())
        )
    }

    private class AccountId(val value: Long)
}