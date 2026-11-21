package com.meridian.payments.intake.auth;

/** Payment providers whose webhook signatures the intake verifies. */
public enum Provider {
    QUETZAL,
    ANDINA,
    /** Chile. A product extension: the original exercise covered Mexico and Colombia. */
    AUSTRAL
}
