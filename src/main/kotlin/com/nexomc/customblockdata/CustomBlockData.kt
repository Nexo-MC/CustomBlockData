/*
 * Copyright (c) 2022 Alexander Majka (mfnalex) / JEFF Media GbR
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License.
 *
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * If you need help or have any suggestions, feel free to join my Discord and head to #programming-help:
 *
 * Discord: https://discord.jeff-media.com/
 *
 * If you find this library helpful or if you're using it one of your paid plugins, please consider leaving a donation
 * to support the further development of this project :)
 *
 * Donations: https://paypal.me/mfnalex
 */
package com.nexomc.customblockdata

import com.nexomc.customblockdata.events.CustomBlockDataRemoveEvent
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.key.Key
import org.apache.commons.lang3.StringUtils
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.persistence.PersistentDataAdapterContext
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.BlockVector
import java.io.IOException
import java.util.*
import java.util.function.Consumer
import kotlin.text.toInt

/**
 * Represents a [PersistentDataContainer] for a specific [Block]. Also provides some static utility methods
 * that can be used on every PersistentDataContainer.
 *
 *
 * By default, and for backward compatibility reasons, data stored inside blocks is independent of the underlying block.
 * That means: if you store some data inside a dirt block, and that block is now pushed by a piston, then the information
 * will still reside in the old block's location. **You can of course also make CustomBockData automatically take care of those situations**,
 * so that CustomBlockData will always be updated on certain Bukkit Events like BlockBreakEvent, EntityExplodeEvent, etc.
 * For more information about this please see [.registerListener].
 */
class CustomBlockData : PersistentDataContainer {
    /**
     * The Chunk PDC belonging to this CustomBlockData object
     */
    private val pdc: PersistentDataContainer

    /**
     * The Chunk this CustomBlockData object belongs to
     */
    private val chunk: Chunk

    /**
     * The NamespacedKey used to identify this CustomBlockData object inside the Chunk's PDC
     */
    private val key: NamespacedKey

    /**
     * The Map.Entry containing the UUID of the block and its BlockVector for usage with [.DIRTY_BLOCKS]
     */
    private val blockEntry: Pair<UUID, BlockVector>

    /**
     * The Plugin this CustomBlockData object belongs to
     */
    private val plugin: Plugin

    /**
     * Gets the PersistentDataContainer associated with the given block and plugin
     *
     * @param block  Block
     * @param plugin Plugin
     */
    constructor(block: Block, plugin: Plugin) {
        this.chunk = block.chunk
        this.key = getKey(plugin, block)
        this.pdc = this.persistentDataContainer
        this.blockEntry = getBlockEntry(block)
        this.plugin = plugin
    }

    constructor(world: World, x: Int, y: Int, z: Int, plugin: Plugin) {
        this.chunk = world.getChunkAt(x, z)
        this.key = NamespacedKey(plugin, getKey(x, y, z))
        this.pdc = this.persistentDataContainer
        this.blockEntry = getBlockEntry(world, x, y, z)
        this.plugin = plugin
    }

    /**
     * Gets the PersistentDataContainer associated with the given block and plugin
     *
     * @param block     Block
     * @param namespace Namespace
     */
    @Deprecated("Use {@link #CustomBlockData(Block, Plugin)} instead.")
    constructor(block: Block, namespace: String) {
        this.chunk = block.chunk
        this.key = NamespacedKey(namespace, getKey(block))
        this.pdc = this.persistentDataContainer
        this.plugin = JavaPlugin.getProvidingPlugin(CustomBlockData::class.java)
        this.blockEntry = getBlockEntry(block)
    }

    /**
     * Gets the Block associated with this CustomBlockData, or null if the world is no longer loaded.
     */
    val block: Block? get() {
        val (uuid, vector) = blockEntry
        val world = Bukkit.getWorld(uuid) ?: return null
        return world.getBlockAt(vector.blockX, vector.blockY, vector.blockZ)
    }

    /**
     * Gets the PersistentDataContainer associated with this block.
     *
     * @return PersistentDataContainer of this block
     */
    private val persistentDataContainer: PersistentDataContainer get() {
        val chunkPDC = chunk.persistentDataContainer
        val blockPDC = chunkPDC.get(key, PersistentDataType.TAG_CONTAINER)
        if (blockPDC != null) return blockPDC
        return chunkPDC.adapterContext.newPersistentDataContainer()
    }

    var isProtected: Boolean
        /**
         * Gets whether this CustomBlockData is protected. Protected CustomBlockData will not be changed by any Bukkit Events
         *
         * @see .registerListener
         */
        get() = has(PERSISTENCE_KEY, DataType.BOOLEAN)
        /**
         * Sets whether this CustomBlockData is protected. Protected CustomBlockData will not be changed by any Bukkit Events
         *
         * @see .registerListener
         */
        set(isProtected) {
            if (isProtected) {
                set(PERSISTENCE_KEY, DataType.BOOLEAN,true)
            } else {
                remove(PERSISTENCE_KEY)
            }
        }

    /**
     * Removes all CustomBlockData and disables the protection status ([.setProtected]
     */
    fun clear() {
        pdc.keys.forEach(pdc::remove)
        save()
    }

    /**
     * Saves the block's [PersistentDataContainer] inside the chunk's PersistentDataContainer
     */
    private fun save() {
        setDirty(plugin, blockEntry)
        if (pdc.isEmpty) {
            chunk.persistentDataContainer.remove(key)
        } else {
            chunk.persistentDataContainer.set(key, PersistentDataType.TAG_CONTAINER, pdc)
        }
    }

    /**
     * Copies all data to another block. Data already present in the destination block will keep intact, unless it gets
     * overwritten by identically named keys. Data in the source block won't be changed.
     */
    fun copyTo(block: Block, plugin: Plugin) {
        val newCbd = CustomBlockData(block, plugin)
        keys.forEach { key ->
            val dataType = getDataType<Any, Any>(this, key) ?: return@forEach
            val value = get(key, dataType) ?: return@forEach
            newCbd.set(key, dataType, value)
        }
    }

    override fun <T, Z : Any> set(namespacedKey: NamespacedKey, persistentDataType: PersistentDataType<T, Z>, z: Z) {
        pdc.set<T, Z>(namespacedKey, persistentDataType, z)
        save()
    }

    override fun <T : Any, Z : Any> has(namespacedKey: NamespacedKey, persistentDataType: PersistentDataType<T, Z>): Boolean {
        return pdc.has(namespacedKey, persistentDataType)
    }

    override fun has(namespacedKey: NamespacedKey): Boolean {
        for (type in PRIMITIVE_DATA_TYPES) {
            if (pdc.has(namespacedKey, type)) return true
        }
        return false
    }

    override fun <T : Any, Z : Any> get(namespacedKey: NamespacedKey, persistentDataType: PersistentDataType<T, Z>): Z? {
        return pdc.get(namespacedKey, persistentDataType)
    }

    override fun <T : Any, Z : Any> getOrDefault(namespacedKey: NamespacedKey, persistentDataType: PersistentDataType<T, Z>, z: Z): Z {
        return pdc.getOrDefault(namespacedKey, persistentDataType, z)
    }

    override fun getKeys(): MutableSet<NamespacedKey> {
        return pdc.keys
    }

    override fun remove(namespacedKey: NamespacedKey) {
        pdc.remove(namespacedKey)
        save()
    }

    override fun isEmpty(): Boolean {
        return pdc.isEmpty
    }

    override fun copyTo(other: PersistentDataContainer, replace: Boolean) {
        pdc.copyTo(other, replace)
    }

    override fun getAdapterContext(): PersistentDataAdapterContext {
        return pdc.adapterContext
    }

    /**
     * @see PersistentDataContainer.serializeToBytes
     */
    @PaperOnly
    @Deprecated("Paper-only")
    @Throws(IOException::class)
    override fun serializeToBytes(): ByteArray {
        return pdc.serializeToBytes()
    }

    /**
     * @see PersistentDataContainer.readFromBytes
     */
    @PaperOnly
    @Deprecated("Paper-only")
    @Throws(IOException::class)
    override fun readFromBytes(bytes: ByteArray, clear: Boolean) {
        pdc.readFromBytes(bytes, clear)
    }

    /**
     * @see PersistentDataContainer.readFromBytes
     */
    @PaperOnly
    @Deprecated("Paper-only")
    @Throws(IOException::class)
    override fun readFromBytes(bytes: ByteArray) {
        pdc.readFromBytes(bytes)
    }

    /**
     * Gets the proper primitive [PersistentDataType] for the given [NamespacedKey]
     *
     * @return The primitive PersistentDataType for the given key, or null if the key doesn't exist
     */
    fun <T : Any, Z : Any> getDataType(key: NamespacedKey): PersistentDataType<T, Z>? {
        return getDataType(this, key)
    }

    /**
     * Indicates a method that only works on Paper and forks, but not on Spigot or CraftBukkit
     */
    @Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
    private annotation class PaperOnly

    object DataType {
        val BOOLEAN: PersistentDataType<Byte, Boolean> = object : PersistentDataType<Byte, Boolean> {
            override fun getPrimitiveType(): Class<Byte> {
                return Byte::class.java
            }

            override fun getComplexType(): Class<Boolean> {
                return Boolean::class.java
            }

            override fun toPrimitive(complex: Boolean, context: PersistentDataAdapterContext): Byte {
                return if (complex) 1.toByte() else 0.toByte()
            }

            override fun fromPrimitive(primitive: Byte, context: PersistentDataAdapterContext): Boolean {
                return primitive == 1.toByte()
            }
        }
    }

    companion object {
        /**
         * The default package name that must be changed
         */
        private val DEFAULT_PACKAGE = charArrayOf(
            'c',
            'o',
            'm',
            '.',
            'j',
            'e',
            'f',
            'f',
            '_',
            'm',
            'e',
            'd',
            'i',
            'a',
            '.',
            'c',
            'u',
            's',
            't',
            'o',
            'm',
            'b',
            'l',
            'o',
            'c',
            'k',
            'd',
            'a',
            't',
            'a'
        )

        /**
         * Set of "dirty block positions", that is blocks that have been modified and need to be saved to the chunk
         */
        private val DIRTY_BLOCKS: MutableSet<Pair<UUID, BlockVector>> = HashSet<Pair<UUID, BlockVector>>()

        /**
         * Builtin list of native PersistentDataTypes
         */
        private val PRIMITIVE_DATA_TYPES = arrayOf<PersistentDataType<*, *>>(
            PersistentDataType.BYTE,
            PersistentDataType.SHORT,
            PersistentDataType.INTEGER,
            PersistentDataType.LONG,
            PersistentDataType.FLOAT,
            PersistentDataType.DOUBLE,
            PersistentDataType.STRING,
            PersistentDataType.BYTE_ARRAY,
            PersistentDataType.INTEGER_ARRAY,
            PersistentDataType.LONG_ARRAY,
            PersistentDataType.TAG_CONTAINER_ARRAY,
            PersistentDataType.TAG_CONTAINER
        )

        /**
         * NamespacedKey for the CustomBlockData "protected" key
         */
        private val PERSISTENCE_KEY: NamespacedKey = NamespacedKey.fromString("customblockdata:protected")!!

        /**
         * The minimum X and Z coordinate of any block inside a chunk.
         */
        private const val CHUNK_MIN_XZ = 0

        /**
         * The maximum X and Z coordinate of any block inside a chunk.
         */
        private const val CHUNK_MAX_XZ = (2 shl 3) - 1

        private var onFolia = false

        init {
            checkRelocation()
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
                onFolia = true
            } catch (e: ClassNotFoundException) {
                onFolia = false
            }
        }

        /**
         * Prints a nag message when the CustomBlockData package is not relocated
         */
        private fun checkRelocation() {
            if (CustomBlockData::class.java.`package`.name == String(DEFAULT_PACKAGE)) {
                val plugin = JavaPlugin.getProvidingPlugin(CustomBlockData::class.java)
                plugin.logger.warning("Nag author(s) ${plugin.pluginMeta.authors.joinToString()} of plugin ${plugin.name} for not relocating the CustomBlockData package.")
            }
        }

        /**
         * Gets the block entry for this block used for [.DIRTY_BLOCKS]
         * @param block Block
         * @return Block entry
         */
        private fun getBlockEntry(block: Block): Pair<UUID, BlockVector> {
            return block.world.uid to BlockVector(block.x, block.y, block.z)
        }

        private fun getBlockEntry(world: World, x: Int, y: Int, z: Int): Pair<UUID, BlockVector> {
            return world.uid to BlockVector(x, y, z)
        }

        /**
         * Checks whether this block is flagged as "dirty"
         * @param block Block
         * @return Whether this block is flagged as "dirty"
         */
        fun isDirty(block: Block): Boolean {
            return getBlockEntry(block) in DIRTY_BLOCKS
        }

        /**
         * Sets this block as "dirty" and removes it from the list after the next tick.
         *
         *
         * If the plugin is disabled, this method will do nothing, to prevent the IllegalPluginAccessException.
         * @param plugin Plugin
         * @param blockEntry Block entry
         */
        fun setDirty(plugin: Plugin, blockEntry: Pair<UUID, BlockVector>) {
            if (!plugin.isEnabled)  //checks if the plugin is disabled to prevent the IllegalPluginAccessException
                return

            DIRTY_BLOCKS.add(blockEntry)
            if (onFolia) {
                Bukkit.getServer().globalRegionScheduler.runDelayed(plugin, Consumer { task: ScheduledTask? ->
                    DIRTY_BLOCKS.remove(blockEntry)
                }, 1L)
            } else {
                Bukkit.getScheduler().runTask(plugin, Runnable { DIRTY_BLOCKS.remove(blockEntry) })
            }
        }

        /**
         * Gets the NamespacedKey for this block
         * @param plugin Plugin
         * @param block Block
         * @return NamespacedKey
         */
        private fun getKey(plugin: Plugin, block: Block): NamespacedKey {
            return NamespacedKey(plugin, getKey(block))
        }

        /**
         * Gets a String-based [NamespacedKey] that consists of the block's relative coordinates within its chunk
         *
         * @param block block
         * @return NamespacedKey consisting of the block's relative coordinates within its chunk
         */
        fun getKey(block: Block): String {
            val x = block.x and 0x000F
            val y = block.y
            val z = block.z and 0x000F
            return "x" + x + "y" + y + "z" + z
        }

        fun getKey(blockX: Int, blockY: Int, blockZ: Int): String {
            val x = blockX and 0x000F
            val z = blockZ and 0x000F
            return "x" + x + "y" + blockY + "z" + z
        }

        /**
         * Gets the block represented by the given [NamespacedKey] in the given [Chunk]
         */
        fun getBlockFromKey(key: NamespacedKey, chunk: Chunk): Block? {
            try {
                val x = StringUtils.substringBetween(key.value(), "x", "y").toInt()
                if (x !in CHUNK_MIN_XZ..CHUNK_MAX_XZ) return null
                val z = StringUtils.substringAfter(key.value(), "z").toInt()
                if (z !in CHUNK_MIN_XZ..CHUNK_MAX_XZ) return null
                val y = StringUtils.substringBetween(key.value(), "y", "Z").toInt()
                val world = chunk.world
                if (y !in world.minHeight..world.maxHeight) return null

                return chunk.getBlock(x, y, z)
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }

        /**
         * Get if the given Block has any CustomBockData associated with it
         */
        fun hasCustomBlockData(block: Block, plugin: Plugin): Boolean {
            return block.chunk.persistentDataContainer.has(getKey(plugin, block), PersistentDataType.TAG_CONTAINER)
        }

        /**
         * Get if the given Block's CustomBlockData is protected. Protected CustomBlockData will not be changed by any Bukkit Events
         *
         * @return true if the Block's CustomBlockData is protected, false if it doesn't have any CustomBlockData or it's not protected
         * @see .registerListener
         */
        fun isProtected(block: Block, plugin: Plugin): Boolean {
            return CustomBlockData(block, plugin).isProtected
        }

        /**
         * Starts to listen and manage block-related events such as [BlockBreakEvent]. By default, CustomBlockData
         * is "stateless". That means: when you add data to a block, and now a player breaks the block, the data will
         * still reside at the original block location. This is to ensure that you always have full control about what data
         * is saved at which location.
         *
         *
         * If you do not want to handle this yourself, you can instead let CustomBlockData handle those events by calling this
         * method once. It will then listen to the common events itself, and automatically remove/update CustomBlockData.
         *
         *
         * Block changes made using the Bukkit API (e.g. [Block.setType]) or using a plugin like WorldEdit
         * will **not** be registered by this (but pull requests are welcome, of course)
         *
         *
         * For example, when you call this method in onEnable, CustomBlockData will now get automatically removed from a block
         * when a player breaks this block. It will additionally call custom events like [CustomBlockDataRemoveEvent].
         * Those events implement [org.bukkit.event.Cancellable]. If one of the CustomBlockData events is cancelled,
         * it will not alter any CustomBlockData.
         *
         * @param plugin Your plugin's instance
         */
        fun registerListener(plugin: Plugin) {
            Bukkit.getPluginManager().registerEvents(BlockDataListener(plugin), plugin)
        }

        /**
         * Returns a Set of all blocks in this chunk containing Custom Block Data created by the given plugin
         *
         * @param plugin Plugin
         * @param chunk  Chunk
         * @return A Set containing all blocks in this chunk containing Custom Block Data created by the given plugin
         */
        fun getBlocksWithCustomData(plugin: Plugin, chunk: Chunk): MutableSet<Block> {
            val dummy = NamespacedKey(plugin, "dummy")
            return getBlocksWithCustomData(chunk, dummy)
        }

        /**
         * Returns a [Set] of all blocks in this [Chunk] containing Custom Block Data matching the given [NamespacedKey]'s namespace
         *
         * @param namespace Namespace
         * @param chunk     Chunk
         * @return A [Set] containing all blocks in this chunk containing Custom Block Data created by the given plugin
         */
        private fun getBlocksWithCustomData(chunk: Chunk, namespace: NamespacedKey): MutableSet<Block> {
            val chunkPDC = chunk.persistentDataContainer
            val blocks: MutableSet<Block> = HashSet<Block>()

            for (key in chunkPDC.keys) {
                if (key.namespace == namespace.namespace) {
                    getBlockFromKey(key, chunk)?.let(blocks::add)
                }
            }
            return blocks
        }

        /**
         * Returns a [Set] of all blocks in this [Chunk] containing Custom Block Data created by the given plugin
         *
         * @param namespace Namespace
         * @param chunk     Chunk
         * @return A [Set] containing all blocks in this chunk containing Custom Block Data created by the given plugin
         */
        fun getBlocksWithCustomData(namespace: String, chunk: Chunk): MutableSet<Block> {
            @Suppress("deprecation") val dummy = NamespacedKey(namespace, "dummy")
            return getBlocksWithCustomData(chunk, dummy)
        }

        /**
         * Gets the proper primitive [PersistentDataType] for the given [NamespacedKey] in the given [PersistentDataContainer]
         *
         * @return The primitive PersistentDataType for the given key, or null if the key doesn't exist
         */
        fun <T : Any, Z : Any> getDataType(pdc: PersistentDataContainer, key: NamespacedKey): PersistentDataType<T, Z>? {
            for (dataType in PRIMITIVE_DATA_TYPES) {
                if (pdc.has(key, dataType)) return dataType as PersistentDataType<T, Z>
            }
            return null
        }
    }
}

