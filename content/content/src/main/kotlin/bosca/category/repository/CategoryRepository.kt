package bosca.category.repository

import bosca.category.model.Category
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface CategoryRepository {

    @Query("select * from categories")
    suspend fun getAll(): List<Category>

    @Query("select * from categories where id = any(:ids)")
    suspend fun getAll(ids: List<UUID>): List<Category>

    @Query("select * from categories where id = :id")
    suspend fun getById(id: UUID): Category?

    @Query("delete from categories where id = :id")
    suspend fun deleteById(id: UUID)

    @Query("insert into categories (name) values (:name) returning *")
    suspend fun addCategory(category: Category): Category

    @Query("update categories set name = :name where id = :id returning *")
    suspend fun editCategory(category: Category): Category
}