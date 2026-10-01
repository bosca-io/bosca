package bosca.graphql.client.generated

interface ISpecFields {
    val id: String
    val key: String
    val status: Status
    val project: Project?

    interface Status {
        val name: String
        val category: String
    }

    interface Project {
        val key: String
        val name: String
    }
}
