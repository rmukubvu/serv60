package com.serv60.workshop.internal.application;

import java.util.UUID;

/** Published inside the transaction that changed a visit on the floor; delivered after commit. */
public record FloorChanged(UUID hubId, UUID visitId) {}
