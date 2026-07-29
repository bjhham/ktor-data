package io.ktor.data.exposed.r2dbc

import io.ktor.data.*
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.*
import kotlin.time.Clock

class VisitsRepositoryTest {

    lateinit var db: R2dbcDatabase
    lateinit var repository: Repository<Visit, UInt>
    lateinit var owners: Repository<Owner, UInt>
    lateinit var vets: Repository<Vet, UInt>
    lateinit var pets: Repository<Pet, UInt>
    lateinit var petTypes: Repository<PetType, UInt>

    @BeforeTest
    fun setup() = runTest {
        db = R2dbcDatabase.connect("r2dbc:h2:mem:///regular;DB_CLOSE_DELAY=-1;")
        suspendTransaction(db) {
            SchemaUtils.create(Visits, Owners, Vets, VetSkills, Pets, PetTypes)
        }
        repository = VisitsRepository(db)
        owners = OwnersRepository(db)
        vets = VetsRepository(db)
        pets = PetsRepository(db)
        petTypes = PetTypesRepository(db)
    }

    @AfterTest
    fun teardown() = runTest {
        suspendTransaction(db) {
            exec("DROP ALL OBJECTS")
        }
    }

    @Test
    fun `1 to 1 relations are populated from the joined row`() = runTest {
        val owner = owners.createAndGet(
            Owner(
                id = 0u,
                firstName = "Alice",
                lastName = "Smith",
                address = "1 Main St",
                city = "Townsville",
                telephone = "555-0100",
            )
        )
        val cat = petTypes.createAndGet(PetType(id = 0u, name = "Cat"))
        val fluffy = pets.createAndGet(Pet(id = 0u, name = "Fluffy", type = cat, owner = owner))
        val vet = vets.createAndGet(Vet(id = 0u, firstName = "Doc", lastName = "Holliday"))

        val now = Clock.System.now()
        val created = repository.createAndGet(
            Visit(
                id = 0u,
                date = now,
                description = "Annual checkup",
                pet = fluffy,
                owner = owner,
                vet = vet,
            )
        )

        val fetched = assertNotNull(repository.get(created.id))
        assertEquals(created.id, fetched.id)
        assertEquals("Annual checkup", fetched.description)
        // 1:1 children are populated by the joined query, including all their column data.
        assertEquals(fluffy.id, fetched.pet.id)
        assertEquals("Fluffy", fetched.pet.name)
        assertEquals(owner, fetched.owner)
        assertEquals(vet, fetched.vet)

        // The same is true when fetched via `list`.
        val listed = repository.all().page()
        assertEquals(1, listed.size)
        assertEquals(owner, listed.single().owner)
        assertEquals(vet, listed.single().vet)
        assertEquals(fluffy.id, listed.single().pet.id)
    }

    @Test
    fun `list returns one entity per visit even with several 1 to 1 joins`() = runTest {
        val alice = owners.createAndGet(
            Owner(0u, "Alice", "Smith", "1 Main", "Town", "555-0100")
        )
        val bob = owners.createAndGet(
            Owner(0u, "Bob", "Jones", "2 Side", "Vale", "555-0200")
        )
        val cat = petTypes.createAndGet(PetType(id = 0u, name = "Cat"))
        val dog = petTypes.createAndGet(PetType(id = 0u, name = "Dog"))
        val fluffy = pets.createAndGet(Pet(id = 0u, name = "Fluffy", type = cat, owner = alice))
        val rex = pets.createAndGet(Pet(id = 0u, name = "Rex", type = dog, owner = bob))
        val doc = vets.createAndGet(Vet(0u, "Doc", "Holliday"))

        val now = Clock.System.now()
        val first = repository.createAndGet(Visit(0u, now, "v1", fluffy, alice, doc))
        val second = repository.createAndGet(Visit(0u, now, "v2", rex, bob, doc))

        val listed = repository.all().list().associateBy { it.id }
        assertEquals(2, listed.size)
        assertEquals(alice, listed[first.id]!!.owner)
        assertEquals(bob, listed[second.id]!!.owner)
        assertEquals(fluffy.id, listed[first.id]!!.pet.id)
        assertEquals(rex.id, listed[second.id]!!.pet.id)
        assertEquals(doc, listed[first.id]!!.vet)
        assertEquals(doc, listed[second.id]!!.vet)
    }
}
