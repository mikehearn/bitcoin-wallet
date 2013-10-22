/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoin;

public class PaymentException extends Exception {
    // TODO: enumify this.
	public static final int NO_SUCH_CHANNEL = -1;
	public static final int CHANNEL_NOT_IN_SPENDABLE_STATE = -2;
	public static final int INVALID_REQUEST = -3;
    public static final int INSUFFICIENT_VALUE = -4;

    private int code;

    public PaymentException(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    @Override
    public String toString() {
        return "PaymentException (" + code + ")";
    }
}
