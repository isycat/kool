package de.fabmax.kool.modules.ksl.lang

import de.fabmax.kool.pipeline.BindGroupScope
import de.fabmax.kool.util.Struct

/**
 * A storage buffer of a program, bound in [scope]: a pipeline's, or a mesh's own ([BindGroupScope.MESH]), so meshes
 * drawn by one shader each read a buffer of their own.
 */
sealed class KslStorage<T: KslStorageType<*>>(
    name: String,
    val storageType: T,
    val size: Int?,
    val scope: BindGroupScope = BindGroupScope.PIPELINE,
) : KslValue<T>(name, true) {
    val name: String
        get() = stateName

    internal var isRead = false
    internal var isWritten = false
    internal var isAccessedAtomically = false
}

class KslStructStorage<T: Struct>(name: String, type: KslStruct<T>, size: Int?, scope: BindGroupScope = BindGroupScope.PIPELINE) :
    KslStorage<KslStructStorageType<T>>(name, KslStructStorageType(type), size, scope)
{
    override val expressionType: KslStructStorageType<T> get() = storageType
}

class KslPrimitiveStorage<T: KslPrimitiveStorageType<*>>(name: String, storage: T, size: Int?, scope: BindGroupScope = BindGroupScope.PIPELINE) :
    KslStorage<T>(name, storage, size, scope)
{
    override val expressionType: T get() = storageType
}

open class KslStorageRead<T: KslStorageType<R>, R: KslType>(
    val storage: KslStorage<T>,
    val index: KslExprInt1,
    override val expressionType: R
) : KslExpression<R> {

    init { storage.isRead = true }
    override fun collectSubExpressions(): List<KslExpression<*>> = collectRecursive(storage, index)
    override fun toPseudoCode(): String = "storageRead(${storage.toPseudoCode()}, ${index.toPseudoCode()})"
}

class KslStorageReadScalar<T: KslStorageType<S>, S>(
    storage: KslStorage<T>,
    index: KslExprInt1,
    override val expressionType: S
) : KslStorageRead<T, S>(storage, index, expressionType), KslScalarExpression<S> where S : KslNumericType, S : KslScalar {

    init { storage.isRead = true }
    override fun collectSubExpressions(): List<KslExpression<*>> = collectRecursive(storage, index)
    override fun toPseudoCode(): String = "storageRead(${storage.toPseudoCode()}, ${index.toPseudoCode()})"
}

class KslStorageReadVector<T: KslStorageType<V>, V, S>(
    storage: KslStorage<T>,
    index: KslExprInt1,
    override val expressionType: V
) : KslStorageRead<T, V>(storage, index, expressionType), KslVectorExpression<V, S> where V : KslNumericType, V : KslVector<S>, S : KslScalar {

    init { storage.isRead = true }
    override fun collectSubExpressions(): List<KslExpression<*>> = collectRecursive(storage, index)
    override fun toPseudoCode(): String = "storageRead(${storage.toPseudoCode()}, ${index.toPseudoCode()})"
}

open class KslStorageWrite<T: KslStorageType<R>, R: KslType>(
    val storage: KslStorage<T>,
    val index: KslExprInt1,
    val data: KslExpression<R>,
    scopeBuilder: KslScopeBuilder
) : KslStatement("store", scopeBuilder) {

    init {
        addExpressionDependencies(storage)
        addExpressionDependencies(index)
        addExpressionDependencies(data)
        addMutation(storage.mutate())

        storage.isWritten = true
    }

    override fun toPseudoCode(): String {
        return annotatePseudoCode("storageWrite(${storage.toPseudoCode()}, ${index.toPseudoCode()}, ${data.toPseudoCode()})")
    }
}
