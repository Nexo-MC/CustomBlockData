package com.nexomc.customblockdata

fun String.substringBetween(after: String, before: String) = substringAfter(after).substringBefore(before)