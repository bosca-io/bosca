package bosca.bml.contract

/** A parameter of a contract function: `name: Type`. */
data class ContractParam(val name: String, val type: String)

/** A contract function: `[suspend] fun name(params): returnType`. */
data class ContractFunction(
    val name: String,
    val isSuspend: Boolean,
    val params: List<ContractParam>,
    val returnType: String,
)

/** A `<contract>` interface declaration. */
data class ContractDecl(val name: String, val functions: List<ContractFunction>)
