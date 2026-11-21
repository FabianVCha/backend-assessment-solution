package com.meridian.payments.intake.store;

/** The two destinations every accepted notification is owed to, independently. */
public enum Destination {
    SALESFORCE,
    CANONICAL_EVENT
}
