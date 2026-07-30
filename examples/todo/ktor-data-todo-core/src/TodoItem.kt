import io.ktor.data.Identifiable
import kotlinx.serialization.Serializable

@Serializable
data class TodoItem(
    override val id: UInt,
    val text: String,
): Identifiable<UInt>