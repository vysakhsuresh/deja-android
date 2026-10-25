package com.layerbit.deja.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.layerbit.deja.R
import com.layerbit.deja.data.model.Category
import com.layerbit.deja.data.model.EntityType

/*
 * What the UI calls a category or an entity type, in the user's language.
 *
 * The enums keep their English `label` on purpose: it is written into the search index, so
 * "pan" and "receipt" keep finding things whatever language the phone is set to. Only what is
 * shown on screen is translated, and it comes from here.
 */

@StringRes
fun Category.labelRes(): Int = when (this) {
    Category.RECEIPT -> R.string.category_receipt
    Category.BANKING -> R.string.category_banking
    Category.TICKET -> R.string.category_ticket
    Category.CODE -> R.string.category_code
    Category.IDENTITY -> R.string.category_identity
    Category.CONTACT -> R.string.category_contact
    Category.CHAT -> R.string.category_chat
    Category.SHOPPING -> R.string.category_shopping
    Category.PLACE -> R.string.category_place
    Category.FOOD -> R.string.category_food
    Category.WORK -> R.string.category_work
    Category.SOCIAL -> R.string.category_social
    Category.MEDIA -> R.string.category_media
    Category.HEALTH -> R.string.category_health
    Category.OTHER -> R.string.category_other
}

@StringRes
fun EntityType.labelRes(): Int = when (this) {
    EntityType.CODE -> R.string.entity_code
    EntityType.WIFI_NETWORK -> R.string.entity_wifi_network
    EntityType.WIFI_PASSWORD -> R.string.entity_wifi_password
    EntityType.CARD -> R.string.entity_card
    EntityType.AADHAAR -> R.string.entity_aadhaar
    EntityType.PAN -> R.string.entity_pan
    EntityType.PASSPORT -> R.string.entity_passport
    EntityType.VOTER_ID -> R.string.entity_voter_id
    EntityType.DRIVING_LICENCE -> R.string.entity_driving_licence
    EntityType.ACCOUNT_NUMBER -> R.string.entity_account_number
    EntityType.IFSC -> R.string.entity_ifsc
    EntityType.GSTIN -> R.string.entity_gstin
    EntityType.UPI_ID -> R.string.entity_upi_id
    EntityType.AMOUNT -> R.string.entity_amount
    EntityType.DATE -> R.string.entity_date
    EntityType.BOOKING_REF -> R.string.entity_booking_ref
    EntityType.FLIGHT -> R.string.entity_flight
    EntityType.TIME -> R.string.entity_time
    EntityType.VEHICLE -> R.string.entity_vehicle
    EntityType.PHONE -> R.string.entity_phone
    EntityType.EMAIL -> R.string.entity_email
    EntityType.HANDLE -> R.string.entity_handle
    EntityType.LINK -> R.string.entity_link
}

@Composable
fun Category.displayName(): String = stringResource(labelRes())

@Composable
fun EntityType.displayName(): String = stringResource(labelRes())
