// Generated from web/src/i18n.ts and component-scoped locale imports by python3 web/tooling/generate_android_strings.py; do not edit.
package assistant.mobile.design

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.RussianPlural

/** Names and placeholder signatures come from GeneratedStrings, never from user input. */
@SuppressLint("DiscouragedApi")
fun Resources.localizedString(message: LocalizedString, packageName: String): String {
    require(message.arguments.size == message.key.parameters.size) {
        "Wrong placeholder count for ${message.key.webKey}"
    }
    val resourceName = if (message.key.pluralForms > 1) {
        val quantity = requireNotNull(message.quantity) { "A plural choice is required" }
        message.key.resourceName + "_form_" + RussianPlural.index(quantity, message.key.pluralForms)
    } else {
        require(message.quantity == null) { "A plain string has no plural choice" }
        message.key.resourceName
    }
    val id = getIdentifier(resourceName, "string", packageName)
    check(id != 0) { "Missing generated string: ${message.key.webKey}" }
    return if (message.arguments.isEmpty()) getString(id)
    else getString(id, *message.arguments.toTypedArray())
}

fun Context.localizedString(message: LocalizedString): String =
    resources.localizedString(message, packageName)
