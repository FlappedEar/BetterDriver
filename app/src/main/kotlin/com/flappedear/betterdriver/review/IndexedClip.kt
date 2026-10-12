package com.flappedear.betterdriver.review

import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.video.VideoClip as IndexedClip

/**
 * A clip of the session's clip index (`clips.json`) on the recorder's terms, for the review, the
 * analysis and the exports. Its camera is the clip's source: "phone", as the phone's live video
 * events name it, or one GoPro (CameraIds.goPro). Not its kind: two GoPros of one kind would make
 * one camera, whose clips cut into each other wherever both recorded.
 */
internal fun recorderClip(c: IndexedClip): VideoClip =
    VideoClip(c.sourceId, c.fileName, c.sessionOffsetUs, c.durationUs?.let(c::sessionTimeUs))
