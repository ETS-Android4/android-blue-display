/*
 * BlueSerial.cpp
 *
 *   SUMMARY
 *  Blue Display is an Open Source Android remote Display for Arduino etc.
 *  It receives basic draw requests from Arduino etc. over Bluetooth and renders it.
 *  It also implements basic GUI elements as buttons and sliders.
 *  GUI callback, touch and sensor events are sent back to Arduino.
 *
 *  Copyright (C) 2014  Armin Joachimsmeyer
 *  armin.joachimsmeyer@gmail.com
 *
 *  This file is part of BlueDisplay.
 *  BlueDisplay is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.

 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.

 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/gpl.html>.
 *
 */

#include <Arduino.h>
#include "BlueSerial.h"
#include "EventHandler.h"

// Simple serial is a simple blocking serial version without receive buffer and other overhead.
// Using it saves up to 1250 byte FLASH and 185 byte RAM since USART is used directly
#define USE_SIMPLE_SERIAL

// Data field types
const int DATAFIELD_TAG_BYTE = 0x01;
const int DATAFIELD_TAG_SHORT = 0x02;
const int DATAFIELD_TAG_INT = 0x03;
const int DATAFIELD_TAG_LONG = 0x04;
const int DATAFIELD_TAG_FLOAT = 0x05;
const int DATAFIELD_TAG_DOUBLE = 0x06;
const int LAST_FUNCTION_TAG_DATAFIELD = 0x07;

#define MAX_NUMBER_OF_ARGS 12 // for sending
#define TOUCH_COMMAND_SIZE_BYTE_MAX  13 // for receiving

// definitions from <wiring_private.h>
#ifndef cbi
#define cbi(sfr, bit) (_SFR_BYTE(sfr) &= ~_BV(bit))
#endif
#ifndef sbi
#define sbi(sfr, bit) (_SFR_BYTE(sfr) |= _BV(bit))
#endif

#ifdef LOCAL_DISPLAY_EXISTS
bool usePairedPin = false;

void setUsePairedPin(bool aUsePairedPin) {
    usePairedPin = aUsePairedPin;
}

bool USART_isBluetoothPaired(void) {
    if (!usePairedPin) {
        return true;
    }
    // use tVal to produce optimal code with the compiler
    uint8_t tVal = digitalReadFast(PAIRED_PIN);
    if (tVal != 0) {
        return true;
    }
    return false;
}
#endif

#ifdef USE_SIMPLE_SERIAL
void initSimpleSerial(uint32_t aBaudRate, bool aUsePairedPin) {
#ifdef LOCAL_DISPLAY_EXISTS
    usePairedPin = aUsePairedPin;
    if (aUsePairedPin) {
        pinMode(PAIRED_PIN, INPUT);
    }
#endif
    uint16_t baud_setting;

    UCSR0A = 1 << U2X0; // Double Speed Mode
    // Exact value = 17,3611 (- 1) for 115200  2,1%
    // 8,68 (- 1) for 230400 8,5% for 8, 3.7% for 9
    // 4,34 (- 1) for 460800 8,5%
    // HC-05 Specified Max Total Error (%) for 8 bit= +3.90/-4.00
    baud_setting = (((F_CPU / 4) / aBaudRate) - 1) / 2;    // /2 after -1 because of better rounding

    // assign the baud_setting, a.k.a. ubbr (USART Baud Rate Register)
    UBRR0H = baud_setting >> 8;
    UBRR0L = baud_setting;

    // enable: TX, RX, RX Complete Interrupt
    UCSR0B = (1 << RXEN0) | (1 << TXEN0) | (1 << RXCIE0);
    remoteTouchEvent.EventType = EVENT_TAG_NO_EVENT;
#ifndef DO_NOT_NEED_BASIC_TOUCH
    remoteTouchDownEvent.EventType = EVENT_TAG_NO_EVENT;
#endif
}

/**
 * ultra simple blocking USART send routine - works 100%!
 */
void USART3_send(char aChar) {
// wait for buffer to become empty
    while (!((UCSR0A) & (1 << UDRE0))) {
        ;
    }
    UDR0 = aChar;
}
#endif

/**
 * TX of USART0 is port D1
 * RX is port D0
 */
/*
 * RECEIVE BUFFER
 */
#define RECEIVE_TOUCH_OR_DISPLAY_DATA_SIZE 4
#define RECEIVE_CALLBACK_DATA_SIZE TOUCH_CALLBACK_DATA_SIZE
//Buffer for 12 bytes since no need for length and eventType and SYNC_TOKEN be stored
uint8_t sReceiveBuffer[RECEIVE_CALLBACK_DATA_SIZE];
uint8_t sReceiveBufferIndex = 0; // Index of first free position in buffer
bool sReceiveBufferOutOfSync = false;

/**
 * very simple blocking USART send routine - works 100%!
 */
void sendUSARTBufferNoSizeCheck(uint8_t * aParameterBufferPointer, int aParameterBufferLength, uint8_t * aDataBufferPointer,
        int16_t aDataBufferLength) {
#ifdef USE_SIMPLE_SERIAL
    while (aParameterBufferLength > 0) {
        // wait for USART send buffer to become empty
        while (!((UCSR0A) & (1 << UDRE0))) {
            ;
        }
        //USART_SendData(USART3, *aBytePtr);
        UDR0 = *aParameterBufferPointer;
        aParameterBufferPointer++;
        aParameterBufferLength--;
    }
    while (aDataBufferLength > 0) {
        // wait for USART send buffer to become empty
        while (!((UCSR0A) & (1 << UDRE0))) {
            ;
        }
        //USART_SendData(USART3, *aBytePtr);
        UDR0 = *aDataBufferPointer;
        aDataBufferPointer++;
        aDataBufferLength--;
    }
#else
    Serial.write(aParameterBufferPointer, aParameterBufferLength);
    Serial.write(aDataBufferPointer, aDataBufferLength);
#endif
}

/**
 * send:
 * 1. Sync Byte A5
 * 2. Byte Function token
 * 3. Short length of parameters (here 5*2)
 * 4. Short n parameters
 */
void sendUSART5Args(uint8_t aFunctionTag, uint16_t aXStart, uint16_t aYStart, uint16_t aXEnd, uint16_t aYEnd, uint16_t aColor) {
    uint16_t tParamBuffer[MAX_NUMBER_OF_ARGS];

    uint16_t * tBufferPointer = &tParamBuffer[0];
    *tBufferPointer++ = aFunctionTag << 8 | SYNC_TOKEN; // add sync token
    *tBufferPointer++ = 10; // parameter length
    *tBufferPointer++ = aXStart;
    *tBufferPointer++ = aYStart;
    *tBufferPointer++ = aXEnd;
    *tBufferPointer++ = aYEnd;
    *tBufferPointer++ = aColor;
    sendUSARTBufferNoSizeCheck((uint8_t*) &tParamBuffer[0], 14, NULL, 0);
}

/**
 *
 * @param aFunctionTag
 * @param aNumberOfArgs currently not more than 12 args are supported
 */
void sendUSARTArgs(uint8_t aFunctionTag, int aNumberOfArgs, ...) {
    if (aNumberOfArgs > MAX_NUMBER_OF_ARGS) {
        return;
    }

    uint16_t tParamBuffer[MAX_NUMBER_OF_ARGS + 2];
    va_list argp;
    uint16_t * tBufferPointer = &tParamBuffer[0];
    *tBufferPointer++ = aFunctionTag << 8 | SYNC_TOKEN; // add sync token
    va_start(argp, aNumberOfArgs);

    *tBufferPointer++ = aNumberOfArgs * 2;
    for (uint8_t i = 0; i < aNumberOfArgs; ++i) {
        *tBufferPointer++ = va_arg(argp, int);
    }
    va_end(argp);
    sendUSARTBufferNoSizeCheck((uint8_t*) &tParamBuffer[0], aNumberOfArgs * 2 + 4, NULL, 0);
}

/**
 *
 * @param aFunctionTag
 * @param aNumberOfArgs currently not more than 12 args are supported
 */
void sendUSARTArgsAndByteBuffer(uint8_t aFunctionTag, int aNumberOfArgs, ...) {
    if (aNumberOfArgs > MAX_NUMBER_OF_ARGS) {
        return;
    }

    uint16_t tParamBuffer[MAX_NUMBER_OF_ARGS + 4];
    va_list argp;
    uint16_t * tBufferPointer = &tParamBuffer[0];
    *tBufferPointer++ = aFunctionTag << 8 | SYNC_TOKEN; // add sync token
    va_start(argp, aNumberOfArgs);

    *tBufferPointer++ = aNumberOfArgs * 2;
    for (uint8_t i = 0; i < aNumberOfArgs; ++i) {
        *tBufferPointer++ = va_arg(argp, int);
    }
    // add data field header
    *tBufferPointer++ = DATAFIELD_TAG_BYTE << 8 | SYNC_TOKEN; // start new transmission block
    uint16_t tLength = va_arg(argp, int); // length in byte
    *tBufferPointer++ = tLength;
    uint8_t * aBufferPtr = (uint8_t *) va_arg(argp, int); // Buffer address
    va_end(argp);

    sendUSARTBufferNoSizeCheck((uint8_t*) &tParamBuffer[0], aNumberOfArgs * 2 + 8, aBufferPtr, tLength);
}

/**
 * Assembles parameter header and appends header for data field
 */
void sendUSART5ArgsAndByteBuffer(uint8_t aFunctionTag, uint16_t aXStart, uint16_t aYStart, uint16_t aXEnd, uint16_t aYEnd,
        uint16_t aColor, uint8_t * aBuffer, uint16_t aBufferLength) {

    uint16_t tParamBuffer[MAX_NUMBER_OF_ARGS];

    uint16_t * tBufferPointer = &tParamBuffer[0];
    *tBufferPointer++ = aFunctionTag << 8 | SYNC_TOKEN; // add sync token
    *tBufferPointer++ = 10; // length
    *tBufferPointer++ = aXStart;
    *tBufferPointer++ = aYStart;
    *tBufferPointer++ = aXEnd;
    *tBufferPointer++ = aYEnd;
    *tBufferPointer++ = aColor;

    // add data field header
    *tBufferPointer++ = DATAFIELD_TAG_BYTE << 8 | SYNC_TOKEN; // start new transmission block
    *tBufferPointer++ = aBufferLength; // length in byte
    sendUSARTBufferNoSizeCheck((uint8_t*) &tParamBuffer[0], 18, aBuffer, aBufferLength);
}

/**
 * Read message in buffer for one event.
 * After RECEIVE_BUFFER_SIZE bytes check if SYNC_TOKEN was sent.
 * If OK then interpret content and reset buffer.
 */
static uint8_t sReceivedEventType = EVENT_TAG_NO_EVENT;

#ifdef USE_SIMPLE_SERIAL
bool allowTouchInterrupts = false; // !!do not enable it, if event handling may take more time than receiving a byte (which then gives buffer overflow)!!!

ISR(USART_RX_vect) {
    uint8_t tByte = UDR0;
    if (sReceiveBufferOutOfSync) {
        // just wait for next sync token and reset buffer
        if (tByte == SYNC_TOKEN) {
            sReceiveBufferOutOfSync = false;
            sReceivedEventType = EVENT_TAG_NO_EVENT;
            sReceiveBufferIndex = 0;
        }
    } else {
        if (sReceivedEventType == EVENT_TAG_NO_EVENT) {
            if (sReceiveBufferIndex == 1) {
                sReceivedEventType = tByte;
                // skip length and eventType
                sReceiveBufferIndex = 0;
            } else {
                // Length not used
                sReceiveBuffer[sReceiveBufferIndex++] = tByte;
            }
        } else {
            uint8_t tDataSize;
            if (sReceivedEventType < EVENT_TAG_FIRST_CALLBACK_ACTION_CODE) {
                // Touch event
                tDataSize = RECEIVE_TOUCH_OR_DISPLAY_DATA_SIZE;
            } else {
                // Callback event
                tDataSize = RECEIVE_CALLBACK_DATA_SIZE;
            }
            if (sReceiveBufferIndex == tDataSize) {
                // now we expect a sync token
                if (tByte == SYNC_TOKEN) {
                    // event complete received
                    // we have one dedicated touch down event in order not to overwrite it with other events before processing it
                    // Yes it makes no sense if interrupts are allowed!
                    struct BluetoothEvent * tRemoteTouchEventPtr = &remoteTouchEvent;
#ifndef DO_NOT_NEED_BASIC_TOUCH
                    if (sReceivedEventType == EVENT_TAG_TOUCH_ACTION_DOWN) {
                        tRemoteTouchEventPtr = &remoteTouchDownEvent;
                    }
#endif
                    tRemoteTouchEventPtr->EventType = sReceivedEventType;
                    // copy buffer to structure
                    memcpy(tRemoteTouchEventPtr->EventData.ByteArray, sReceiveBuffer, tDataSize);
                    sReceiveBufferIndex = 0;
                    sReceivedEventType = EVENT_TAG_NO_EVENT;

                    if (allowTouchInterrupts) {
                        // Dangerous, it blocks receive event as long as event handling goes on!!!
                        handleEvent(tRemoteTouchEventPtr);
                    }
                } else {
                    // reset buffer since we had an overflow or glitch
                    sReceiveBufferOutOfSync = true;
                    sReceiveBufferIndex = 0;
                }
            } else {
                // plain message byte
                sReceiveBuffer[sReceiveBufferIndex++] = tByte;
            }
        }
    }
}
#else

/*
 * Will be called after each loop() (by serial...) to process input data if available.
 */
void serialEvent(void) {
    if (sReceiveBufferOutOfSync) {
// just wait for next sync token
        while (Serial.available() > 0) {
            if (Serial.read() == SYNC_TOKEN) {
                sReceiveBufferOutOfSync = false;
                sReceivedEventType = EVENT_TAG_NO_EVENT;
                break;
            }
        }
    }
    if (!sReceiveBufferOutOfSync) {
        /*
         * regular operation here
         */
        uint8_t tBytesAvailable = Serial.available();
        /*
         * enough bytes available for next step?
         */
        if (sReceivedEventType == EVENT_TAG_NO_EVENT) {
            if (tBytesAvailable >= 2) {
                /*
                 * read message length and event tag first
                 */
                Serial.readBytes((char *) sReceiveBuffer, 2);
                // length is not needed
                sReceivedEventType = sReceiveBuffer[1];
                tBytesAvailable -= 2;
            }
        }
        if (sReceivedEventType != EVENT_TAG_NO_EVENT) {
            uint8_t tDataSize;
            if (sReceivedEventType < EVENT_TAG_FIRST_CALLBACK_ACTION_CODE) {
                // Touch event
                tDataSize = RECEIVE_TOUCH_OR_DISPLAY_DATA_SIZE;
            } else {
                // Callback event
                tDataSize = RECEIVE_CALLBACK_DATA_SIZE;
            }
            if (tBytesAvailable > tDataSize) {
                // touch or size event complete received, now read data and sync token
                Serial.readBytes((char *) sReceiveBuffer, tDataSize);
                if (Serial.read() == SYNC_TOKEN) {
                    remoteTouchEvent.EventType = sReceivedEventType;
                    // copy buffer to structure
                    memcpy(remoteTouchEvent.EventData.ByteArray, sReceiveBuffer, tDataSize);
                    sReceivedEventType = EVENT_TAG_NO_EVENT;
                    handleEvent(&remoteTouchEvent);
                } else {
                    sReceiveBufferOutOfSync = true;
                }
            }
        }
    }
}
#endif