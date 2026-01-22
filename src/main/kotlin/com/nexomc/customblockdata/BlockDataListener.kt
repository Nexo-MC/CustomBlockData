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

import com.nexomc.customblockdata.events.CustomBlockDataMoveEvent
import com.nexomc.customblockdata.events.CustomBlockDataRemoveEvent
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.PistonMoveReaction
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.*
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.plugin.Plugin
import java.util.*
import java.util.function.Consumer
import java.util.function.Predicate

internal class BlockDataListener(private val plugin: Plugin) : Listener {
    private val customDataPredicate: (Block) -> Boolean = { block: Block ->
        CustomBlockData.hasCustomBlockData(block, plugin)
    }

    private fun getCbd(event: BlockEvent): CustomBlockData {
        return getCbd(event.getBlock())
    }

    private fun getCbd(block: Block): CustomBlockData {
        return CustomBlockData(block, plugin)
    }

    private fun callAndRemove(blockEvent: BlockEvent) {
        if (callEvent(blockEvent)) {
            getCbd(blockEvent).clear()
        }
    }

    private fun callEvent(blockEvent: BlockEvent): Boolean {
        return callEvent(blockEvent.getBlock(), blockEvent)
    }

    private fun callEvent(block: Block, bukkitEvent: Event): Boolean {
        if (!CustomBlockData.hasCustomBlockData(block, plugin) || CustomBlockData.isProtected(block, plugin)) return false

        return CustomBlockDataRemoveEvent(plugin, block, bukkitEvent).callEvent()
    }

    private fun callAndRemoveBlockStateList(blockStates: List<BlockState>, bukkitEvent: Event) {
        blockStates.forEach { if (customDataPredicate(it.block)) callAndRemove(it.block, bukkitEvent) }
    }

    private fun callAndRemoveBlockList(blocks: List<Block>, bukkitEvent: Event) {
        blocks.forEach { if (customDataPredicate(it)) callAndRemove(it, bukkitEvent) }
    }

    private fun callAndRemove(block: Block, bukkitEvent: Event) {
        if (callEvent(block, bukkitEvent)) getCbd(block).clear()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun BlockBreakEvent.onBreak() {
        callAndRemove(this)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun BlockPlaceEvent.onPlace() {
        if (!CustomBlockData.isDirty(block)) callAndRemove(this)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun EntityChangeBlockEvent.onEntity() {
        if (to != block.type) callAndRemove(block, this)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun BlockExplodeEvent.onExplode() {
        callAndRemoveBlockList(blockList(), this)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun EntityExplodeEvent.onExplode() {
        callAndRemoveBlockList(blockList(), this)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun BlockBurnEvent.onBurn() {
        callAndRemove(this)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun BlockPistonExtendEvent.onPiston() {
        onPiston(blocks, this)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun BlockPistonRetractEvent.onPiston() {
        onPiston(blocks, this)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun BlockFadeEvent.onFade() {
        if (block.type == Material.FIRE) return
        if (newState.type != block.type) callAndRemove(this)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun StructureGrowEvent.onStructure() {
        callAndRemoveBlockStateList(blocks, this)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun BlockFertilizeEvent.onFertilize() {
        callAndRemoveBlockStateList(blocks, this)
    }

    private fun onPiston(blocks: List<Block>, bukkitEvent: BlockPistonEvent) {
        val map = LinkedHashMap<Block, CustomBlockData>()
        val direction = bukkitEvent.direction
        blocks.filter(customDataPredicate).forEach { block ->
            val cbd = CustomBlockData(block, plugin)
            if (cbd.isEmpty || cbd.isProtected) return@forEach
            if (block.pistonMoveReaction == PistonMoveReaction.BREAK) return@forEach callAndRemove(block, bukkitEvent)

            val destBlock = block.getRelative(direction)
            val moveEvent = CustomBlockDataMoveEvent(plugin, block, destBlock, bukkitEvent)
            if (!moveEvent.callEvent()) return@forEach

            map[destBlock] = cbd
        }

        map.entries.reversed().forEach { (block, cbd) ->
            cbd.copyTo(block, plugin)
            cbd.clear()
        }
    }
}
