/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.service.users;

/**
 * Invalid user login exception with a status.
 */
public class StatusUserLoginException extends UserLoginException {

  /**
   * Login error status.
   */
  public enum Status {
    UNKNOWN,
    INVALID_CREDENTIAL,
    NOT_FOUND,
    INVALID_TYPE,
    INACTIVE
  }

  private final Status errorStatus;

  public StatusUserLoginException(final String userName, final String error) {
    super(userName, error);
    this.errorStatus = Status.UNKNOWN;
  }

  public StatusUserLoginException(final Status errorStatus, final String userName, final String error) {
    super(userName, error);
    this.errorStatus = errorStatus;
  }

  public Status getErrorStatus() {
    return errorStatus;
  }
}
