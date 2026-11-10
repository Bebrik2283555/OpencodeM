package com.opencode.data

/** A stale HTTP snapshot must not undo a newer stream event or an in-flight prompt. */
fun canReconcileActivity(sending: Boolean, before: Long?, latest: Long?) = !sending && before == latest

fun staleActivity(now: Long, last: Long?, sending: Boolean) = !sending && now - (last ?: 0) >= 20_000
