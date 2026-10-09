package com.stackwizard.booking_api.support;

import java.util.List;
import java.util.Set;

/**
 * Which tenant owns a table's rows. Every row has a single {@code tenant_id}; this is the level of that id.
 * <ul>
 *   <li>{@link #ORG} — hotel chain (Platform parent): shared catalog, CRM, events, sales documents</li>
 *   <li>{@link #PROPERTY} — hotel (Platform child): capacity and operations</li>
 *   <li>{@link #USER} — people belong to the chain and work in several hotels</li>
 *   <li>{@link #SYSTEM} — tenant registry itself</li>
 * </ul>
 * Tables without a {@code tenant_id} column inherit the level of their parent row and are not listed.
 * Keep in sync with {@code booking_promote_catalog} (V110); {@code TenantLevelTest} fails when a new
 * tenant-scoped table is not classified here.
 */
public enum TenantLevel {
    ORG,
    PROPERTY,
    USER,
    SYSTEM;

    /** Tables moved from a hotel to its chain by {@code booking_promote_catalog}. */
    public static final List<String> ORG_TABLES = List.of(
            "product", "product_component", "product_package_listing", "price_profile",
            "event", "event_order", "event_status_history", "portal_access_token",
            "sales_quote", "sales_quote_version", "sales_quote_line", "sales_quote_approval",
            "sales_contract", "sales_contract_document", "sales_payment_milestone",
            "crm_account", "crm_account_contact_role", "crm_account_relation", "crm_activity", "crm_alert",
            "crm_assignment_rule", "crm_attachment", "crm_contact", "crm_cost_item",
            "crm_custom_field_definition", "crm_lead", "crm_opportunity", "crm_outcome_reason",
            "crm_pipeline", "crm_pipeline_stage", "crm_reassignment", "crm_segment", "crm_stage_requirement",
            "crm_stage_transition", "crm_team", "crm_team_member", "crm_team_property");

    public static final Set<String> PROPERTY_TABLES = Set.of(
            "allocation", "api_token", "booking_calendar", "cancellation_policy", "cancellation_request",
            "deposit_policy", "event_function", "event_function_item",
            "fiscal_business_premise", "fiscal_cash_register", "function_space", "function_space_setup", "invoice", "invoice_sequence", "location_node",
            "opera_fiscal_charge_mapping", "opera_fiscal_payment_mapping", "opera_fiscal_tax_mapping",
            "opera_fiscal_udf_mapping", "opera_hotel", "opera_invoice_type_routing", "payment_card_type",
            "payment_intent", "payment_transaction", "product_property", "reservation", "reservation_request",
            "reservation_request_access_token", "reservation_request_amendment", "resource",
            "resource_composition", "resource_map", "resource_setup_capacity", "tenant_config",
            "tenant_payment_provider_config");

    public static final Set<String> USER_TABLES = Set.of("app_user");

    public static final Set<String> SYSTEM_TABLES = Set.of("platform_tenant_mapping");

    public static TenantLevel of(String table) {
        if (ORG_TABLES.contains(table)) {
            return ORG;
        }
        if (PROPERTY_TABLES.contains(table)) {
            return PROPERTY;
        }
        if (USER_TABLES.contains(table)) {
            return USER;
        }
        if (SYSTEM_TABLES.contains(table)) {
            return SYSTEM;
        }
        return null;
    }
}
