package com.cms.core.account;

import java.io.Serializable;

public record AdminIdentity(
    String id,
    String loginId,
    String displayName,
    boolean active,
    long authVersion,
    boolean mustChangePassword,
    boolean systemAdmin)
    implements Serializable {}
