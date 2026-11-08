package com.cms.core.permission;

public enum Action {
  READ,
  MANAGE;

  public boolean includes(Action requested) {
    return this == MANAGE || this == requested;
  }
}
