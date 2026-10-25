package assistant.mobile.android

/** Same-origin validation belongs to the Activity; this parser accepts only Circle routes. */
internal fun circleDestination(parts: List<String>): Destination? {
    fun validId(value: String) = value.isNotBlank() && value.length <= 128 &&
        value !in setOf(".", "..") && value.none { it.isISOControl() || it in "/?#\\" }
    return when {
        parts == listOf("settings", "sharing") || parts == listOf("settings", "access") ->
            Destination.Section(FamilyScreen.SharingSettings)
        parts == listOf("circle", "shared") || parts == listOf("settings", "shared") ->
            Destination.Section(FamilyScreen.Shared)
        parts == listOf("community") || parts == listOf("circle") -> Destination.Section(FamilyScreen.Circle)
        parts == listOf("circle", "friends") -> Destination.Section(FamilyScreen.Friends)
        parts.size == 3 && parts.take(2) == listOf("circle", "chat") && validId(parts[2]) ->
            Destination.Detail(FamilyDetail.Channel, parts[2])
        parts.size == 3 && parts.take(2) == listOf("circle", "spaces") && validId(parts[2]) ->
            Destination.Detail(FamilyDetail.Space, parts[2])
        parts.size == 4 && parts.take(2) == listOf("circle", "spaces") &&
            parts[3] == "members" && validId(parts[2]) -> Destination.Detail(FamilyDetail.SpaceMembers, parts[2])
        parts.size == 4 && parts.take(2) == listOf("circle", "spaces") &&
            parts[3] == "challenges" && validId(parts[2]) -> Destination.Detail(FamilyDetail.SpaceChallenges, parts[2])
        parts.size == 3 && parts.take(2) == listOf("circle", "challenges") && validId(parts[2]) ->
            Destination.Detail(FamilyDetail.Challenge, parts[2])
        else -> null
    }
}

/** The web shared view accepts only a resource identity and an optional ISO owner-day. */
internal fun sharedProjectionDestination(id: String?, day: String?): Destination? {
    if (id == null) return if (day == null) Destination.Section(FamilyScreen.Shared) else null
    if (id.isBlank() || id.length > 128 || id in setOf(".", "..") || id.any { it.isISOControl() || it in "/?#\\" }) return null
    if (day != null && runCatching { java.time.LocalDate.parse(day).toString() == day }.getOrDefault(false) != true) return null
    return Destination.SharedProjection(id, day)
}
