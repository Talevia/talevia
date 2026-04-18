package io.talevia.core

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@JvmInline @Serializable value class SessionId(val value: String)
@JvmInline @Serializable value class MessageId(val value: String)
@JvmInline @Serializable value class PartId(val value: String)
@JvmInline @Serializable value class ProjectId(val value: String)
@JvmInline @Serializable value class AssetId(val value: String)
@JvmInline @Serializable value class TrackId(val value: String)
@JvmInline @Serializable value class ClipId(val value: String)
@JvmInline @Serializable value class CallId(val value: String)
