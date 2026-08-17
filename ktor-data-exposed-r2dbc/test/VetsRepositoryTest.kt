package io.ktor.data.exposed.r2dbc

import io.ktor.data.Field
import io.ktor.data.Repository
import io.ktor.data.Predicate.*
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VetsRepositoryTest {

    lateinit var db: R2dbcDatabase
    lateinit var repository: Repository<Vet, UInt>
    lateinit var skills: Repository<Skill, UInt>

    @BeforeTest
    fun setup() = runTest {
        db = R2dbcDatabase.connect("r2dbc:h2:mem:///regular;DB_CLOSE_DELAY=-1;")
        suspendTransaction(db) {
            SchemaUtils.create(Vets, Skills, VetSkills)
        }
        repository = VetsRepository(db)
        skills = SkillsRepository(db)
    }

    @AfterTest
    fun teardown() = runTest {
        suspendTransaction(db) {
            exec("DROP ALL OBJECTS")
        }
    }

    @Test
    fun `basic crud`() = runTest {
        val first = repository.createAndGet(Vet(id = 0u, firstName = "Bob", lastName = "Loblaw"))
        val second = repository.createAndGet(Vet(id = 0u, firstName = "Jane", lastName = "Doe"))
        assertEquals(listOf(first, second), repository.all().list())
        val updated = first.copy(lastName = "Clawbot")
        repository.update(updated)
        assertEquals(updated, repository.get(first.id))
        repository.delete(second.id)
        assertEquals(listOf(updated), repository.all().list())
    }

    @Test
    fun `test joined skills`() = runTest {
        val noSkills    = repository.createAndGet(Vet(id = 0u, firstName = "Alex", lastName = "None"))
        val oneSkill    = repository.createAndGet(Vet(id = 0u, firstName = "Bob",  lastName = "One"))
        val twoSkills   = repository.createAndGet(Vet(id = 0u, firstName = "Cara", lastName = "Two"))

        val dentistry = skills.createAndGet(Skill(id = 0u, name = "Dentistry"))
        val surgery   = skills.createAndGet(Skill(id = 0u, name = "Surgery"))

        assertEquals(
            listOf(dentistry, surgery),
            skills.find(OneOf(Field<UInt>("id"), listOf(dentistry.id, surgery.id))).list()
        )

        suspendTransaction(db) {
            VetSkills.insert {
                it[vetId] = oneSkill.id
                it[skillId] = dentistry.id
            }
            VetSkills.insert {
                it[vetId] = twoSkills.id
                it[skillId] = dentistry.id
            }
            VetSkills.insert {
                it[vetId] = twoSkills.id
                it[skillId] = surgery.id
            }
        }

        assertEquals(emptyList(), assertNotNull(repository.get(noSkills.id)).skills)
        assertEquals(listOf(dentistry), assertNotNull(repository.get(oneSkill.id)).skills)
        assertEquals(setOf(dentistry, surgery), assertNotNull(repository.get(twoSkills.id)).skills.toSet())

        val listed = repository.all().list().associateBy { it.id }
        assertEquals(3, listed.size)
        assertEquals(emptyList(), listed[noSkills.id]!!.skills)
        assertEquals(listOf(dentistry), listed[oneSkill.id]!!.skills)
        assertEquals(setOf(dentistry, surgery), listed[twoSkills.id]!!.skills.toSet())
    }

    @Test
    fun `find total counts distinct vets not joined rows`() = runTest {
        // One vet with no skills, one with a single skill, and one with two skills. The
        // joined query fans the last vet out to two rows, so a naive count over the join
        // would report 4 — but `total` should always reflect the 3 distinct base entities.
        val noSkills  = repository.createAndGet(Vet(id = 0u, firstName = "Alex", lastName = "None"))
        val oneSkill  = repository.createAndGet(Vet(id = 0u, firstName = "Bob",  lastName = "One"))
        val twoSkills = repository.createAndGet(Vet(id = 0u, firstName = "Cara", lastName = "Two"))

        val dentistry = skills.createAndGet(Skill(id = 0u, name = "Dentistry"))
        val surgery   = skills.createAndGet(Skill(id = 0u, name = "Surgery"))

        suspendTransaction(db) {
            VetSkills.insert {
                it[vetId] = oneSkill.id
                it[skillId] = dentistry.id
            }
            VetSkills.insert {
                it[vetId] = twoSkills.id
                it[skillId] = dentistry.id
            }
            VetSkills.insert {
                it[vetId] = twoSkills.id
                it[skillId] = surgery.id
            }
        }

        val page = repository.all().page()
        assertEquals(3, page.size)
        assertEquals(3u, page.total)
    }

    @Test
    fun `find limit on a joined source returns whole parents and stops streaming`() = runTest {
        // Three vets, each with two skills. Without a streaming short-circuit the joined
        // query would materialize 6 rows; with `limit = 2` we should still receive exactly
        // 2 vets and — critically — each must carry its FULL set of skills, not be cut off
        // mid-way through the join fan-out.
        val dentistry = skills.createAndGet(Skill(id = 0u, name = "Dentistry"))
        val surgery   = skills.createAndGet(Skill(id = 0u, name = "Surgery"))

        val alex  = repository.createAndGet(Vet(id = 0u, firstName = "Alex",  lastName = "A"))
        val bob   = repository.createAndGet(Vet(id = 0u, firstName = "Bob",   lastName = "B"))
        val cara  = repository.createAndGet(Vet(id = 0u, firstName = "Cara",  lastName = "C"))

        suspendTransaction(db) {
            for (vet in listOf(alex, bob, cara)) {
                VetSkills.insert {
                    it[vetId] = vet.id
                    it[skillId] = dentistry.id
                }
                VetSkills.insert {
                    it[vetId] = vet.id
                    it[skillId] = surgery.id
                }
            }
        }

        val page = repository.all().page(limit = 2u)
        assertEquals(2, page.size, "limit should cap the result to 2 distinct vets")
        // total still reflects all matching vets, independent of the limit and the join.
        assertEquals(3u, page.total)
        // The two parents we did return must each carry both skills (i.e. the limit didn't
        // chop off the second join row for the second vet).
        for (vet in page) {
            assertEquals(setOf(dentistry, surgery), vet.skills.toSet(), "vet ${vet.id} lost a skill")
        }
        // And they should be the first two by id, since the join is ordered by table.id.
        assertEquals(listOf(alex.id, bob.id), page.map { it.id })
    }

    @Test
    fun `update replaces the skills mapping`() = runTest {
        val dentistry = skills.createAndGet(Skill(id = 0u, name = "Dentistry"))
        val surgery   = skills.createAndGet(Skill(id = 0u, name = "Surgery"))
        val radiology = skills.createAndGet(Skill(id = 0u, name = "Radiology"))

        val vet = repository.createAndGet(
            Vet(id = 0u, firstName = "Dana", lastName = "Up", skills = listOf(dentistry, surgery))
        )
        assertEquals(setOf(dentistry, surgery), assertNotNull(repository.get(vet.id)).skills.toSet())

        // Drop `surgery`, add `radiology`. The update must rewrite the join rows for this vet
        // and leave other vets' rows alone.
        repository.update(vet.copy(skills = listOf(dentistry, radiology)))

        val reloaded = assertNotNull(repository.get(vet.id))
        assertEquals(setOf(dentistry, radiology), reloaded.skills.toSet())

        // Update with an empty list should clear all join rows for this vet.
        repository.update(vet.copy(skills = emptyList()))
        assertEquals(emptyList(), assertNotNull(repository.get(vet.id)).skills)
    }

}
