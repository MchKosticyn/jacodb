package org.utbot.jcdb.impl.features

import org.jooq.DSLContext
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.utbot.jcdb.api.ByteCodeIndexer
import org.utbot.jcdb.api.JCDB
import org.utbot.jcdb.api.JCDBPersistence
import org.utbot.jcdb.api.JcClasspath
import org.utbot.jcdb.api.JcFeature
import org.utbot.jcdb.api.JcSignal
import org.utbot.jcdb.api.RegisteredLocation
import org.utbot.jcdb.impl.fs.ClassSourceImpl
import org.utbot.jcdb.impl.storage.BatchedSequence
import org.utbot.jcdb.impl.storage.eqOrNull
import org.utbot.jcdb.impl.storage.executeQueries
import org.utbot.jcdb.impl.storage.jooq.tables.references.CALLS
import org.utbot.jcdb.impl.storage.jooq.tables.references.CLASSES
import org.utbot.jcdb.impl.storage.jooq.tables.references.SYMBOLS
import org.utbot.jcdb.impl.storage.runBatch
import org.utbot.jcdb.impl.storage.setNullableLong
import org.utbot.jcdb.impl.vfs.LazyPersistentByteCodeLocation


class UsagesIndexer(private val persistence: JCDBPersistence, private val location: RegisteredLocation) : ByteCodeIndexer {

    // callee_class -> (callee_name, callee_desc, opcode) -> caller
    private val usages = hashMapOf<String, HashMap<Triple<String, String?, Int>, HashMap<String, HashSet<Int>>>>()
    private val interner = persistence.newSymbolInterner()

    override fun index(classNode: ClassNode) {
        val callerClass = Type.getObjectType(classNode.name).className
        var callerMethodOffset = 0
        classNode.methods.forEach { methodNode ->
            methodNode.instructions.forEach {
                var key: Triple<String, String?, Int>? = null
                var callee: String? = null
                when (it) {
                    is FieldInsnNode -> {
                        callee = Type.getObjectType(it.owner).className
                        key = Triple(it.name, null, it.opcode)
                    }

                    is MethodInsnNode -> {
                        callee = Type.getObjectType(it.owner).className
                        key = Triple(it.name, it.desc, it.opcode)
                    }
                }
                if (key != null && callee != null) {
                    callee.symbolId
                    key.first.symbolId
                    key.second?.symbolId
                    usages.getOrPut(callee) { hashMapOf() }
                        .getOrPut(key) { hashMapOf() }
                        .getOrPut(callerClass) { hashSetOf() }
                        .add(callerMethodOffset)
                }
            }
            callerMethodOffset++
        }
    }

    override fun flush(jooq: DSLContext) {
        jooq.connection { conn ->
            interner.flush(conn)
            conn.runBatch(CALLS) {
                usages.forEach { (calleeClass, calleeEntry) ->
                    calleeEntry.forEach { (info, callers) ->
                        val (calleeName, calleeDesc, opcode) = info
                        callers.forEach { (caller, offsets) ->
                            setLong(1, calleeClass.existedSymbolId)
                            setLong(2, calleeName.existedSymbolId)
                            setNullableLong(3, calleeDesc?.existedSymbolId)
                            setInt(4, opcode)
                            setLong(5, caller.existedSymbolId)
                            setString(6, offsets.joinToString(","))
                            setLong(7, location.id)
                            addBatch()
                        }
                    }
                }
            }
        }
    }

    private inline val String.symbolId get() = interner.findOrNew(this)
    private inline val String.existedSymbolId get() = persistence.findSymbolId(this)!!
}


object Usages : JcFeature<UsageFeatureRequest, UsageFeatureResponse> {

    private val createScheme = """
        CREATE TABLE IF NOT EXISTS "Calls"(
            "callee_class_symbol_id"      BIGINT NOT NULL,
            "callee_name_symbol_id"       BIGINT NOT NULL,
            "callee_desc_symbol_id"       BIGINT,
            "opcode"                      INTEGER,
            "caller_class_symbol_id"      BIGINT NOT NULL,
            "caller_method_offsets"       VARCHAR(256),
            "location_id"                 BIGINT NOT NULL
            --CONSTRAINT "fk_callee_class_symbol_id" FOREIGN KEY ("callee_class_symbol_id") REFERENCES "Symbols" ("id") ON DELETE CASCADE,
            --CONSTRAINT "fk_callee_name_symbol_id" FOREIGN KEY ("callee_name_symbol_id") REFERENCES "Symbols" ("id") ON DELETE RESTRICT ON UPDATE RESTRICT,
            --CONSTRAINT "fk_callee_desc_symbol_id" FOREIGN KEY ("callee_desc_symbol_id") REFERENCES "Symbols" ("id") ON DELETE RESTRICT ON UPDATE RESTRICT,
            --CONSTRAINT "fk_caller_class_symbol_id" FOREIGN KEY ("caller_class_symbol_id") REFERENCES "Symbols" ("id") ON DELETE CASCADE
            --CONSTRAINT "fk_location_id" FOREIGN KEY ("caller_class_symbol_id") REFERENCES "BytecodeLocations" ("id") ON DELETE CASCADE ON UPDATE RESTRICT
        );
    """.trimIndent()

    private val createIndex = """
        CREATE INDEX IF NOT EXISTS 'Calls search' ON Calls(callee_class_symbol_id, opcode, location_id, callee_name_symbol_id, callee_desc_symbol_id)
    """.trimIndent()

    private val dropScheme = """
        DROP TABLE IF EXISTS "Calls";
        DROP INDEX IF EXISTS "Calls search";
    """.trimIndent()

    override fun onSignal(signal: JcSignal) {
        when (signal) {
            is JcSignal.BeforeIndexing -> {
                signal.jcdb.persistence.write {
                    if (signal.clearOnStart) {
                        it.executeQueries(dropScheme)
                    }
                    it.executeQueries(createScheme)
                }
            }

            is JcSignal.LocationRemoved -> {
                signal.jcdb.persistence.write {
                    it.delete(CALLS).where(CALLS.LOCATION_ID.eq(signal.location.id)).execute()
                }
            }

            is JcSignal.AfterIndexing -> {
                signal.jcdb.persistence.write {
                    it.execute(createIndex)
                }
            }

            is JcSignal.Drop -> {
                signal.jcdb.persistence.write {
                    it.delete(CALLS).execute()
                }
            }

            else -> Unit
        }
    }

    override suspend fun query(classpath: JcClasspath, req: UsageFeatureRequest): Sequence<UsageFeatureResponse> {
        val locationIds = classpath.registeredLocations.map { it.id }
        val persistence = classpath.db.persistence
        val name = (req.methodName ?: req.field).let { persistence.findSymbolId(it!!) }
        val desc = req.description?.let { persistence.findSymbolId(it) }
        val className = persistence.findSymbolId(req.className)
        return BatchedSequence(50) { offset, batchSize ->
            persistence.read { jooq ->
                jooq.select(CLASSES.ID, CALLS.CALLER_METHOD_OFFSETS, SYMBOLS.NAME, CLASSES.BYTECODE, CLASSES.LOCATION_ID)
                    .from(CALLS)
                    .join(SYMBOLS).on(SYMBOLS.ID.eq(CLASSES.NAME))
                    .join(CLASSES).on(CLASSES.NAME.eq(CALLS.CALLER_CLASS_SYMBOL_ID))
                    .where(
                        CALLS.CALLER_CLASS_SYMBOL_ID.eq(className)
                            .and(CALLS.CALLEE_NAME_SYMBOL_ID.eq(name))
                            .and(CALLS.CALLEE_DESC_SYMBOL_ID.eqOrNull(desc))
                            .and(CALLS.OPCODE.`in`(req.opcodes))
                            .and(CALLS.LOCATION_ID.`in`(locationIds))
                    )
                    .orderBy(CLASSES.ID)
                    .limit(batchSize).offset(offset ?: 0)
                    .fetch()
                    .mapNotNull { (classId, offset, className, byteCode, locationId) ->
                        val offsets = offset!!.split(",").map { it.toInt() }
                        classId!! to
                                UsageFeatureResponse(
                                    source = ClassSourceImpl(
                                        LazyPersistentByteCodeLocation(persistence, locationId!!),
                                        className!!, byteCode!!
                                    ),
                                    offsets = offsets
                                )
                    }
            }
        }

    }

    override fun newIndexer(jcdb: JCDB, location: RegisteredLocation) = UsagesIndexer(jcdb.persistence, location)

}