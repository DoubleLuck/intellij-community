// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.lang.properties.charset;

import org.jetbrains.annotations.NotNull;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;

final class Native2AsciiCharsetDecoder extends CharsetDecoder {
  private static final char INVALID_CHAR = (char)-1;
  private StringBuilder myOutBuffer = new StringBuilder();
  private final Charset myBaseCharset;

  Native2AsciiCharsetDecoder(@NotNull Native2AsciiCharset charset) {
    super(charset, 1, 6);
    myBaseCharset = charset.getBaseCharset();
  }

  @Override
  protected void implReset() {
    super.implReset();
    myOutBuffer = new StringBuilder();
  }

  @Override
  protected CoderResult implFlush(CharBuffer out) {
    return doFlush(out);
  }

  @NotNull
  private CoderResult doFlush(@NotNull CharBuffer out) {
    if (myOutBuffer.length() != 0) {
      int remaining = out.remaining();
      int outLen = Math.min(remaining, myOutBuffer.length());
      out.append(myOutBuffer, 0, outLen);
      myOutBuffer.delete(0, outLen);
      if (myOutBuffer.length() != 0) return CoderResult.OVERFLOW;
    }
    return CoderResult.UNDERFLOW;
  }

  @Override
  protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
    try {
      CoderResult coderResult = doFlush(out);
      if (coderResult == CoderResult.OVERFLOW) return CoderResult.OVERFLOW;

      int start = in.position();
      byte[] buf = new byte[4];
      while (in.position() < in.limit()) {
        in.mark();
        byte b = in.get();
        if (b == '\\') {
          decodeArray(in.array(), start, in.position()-1);
          byte next = in.get();
          if (next == 'u') {
            buf[0] = in.get();
            buf[1] = in.get();
            buf[2] = in.get();
            buf[3] = in.get();
            char decoded = unicode(buf);
            if (decoded == INVALID_CHAR) {
              myOutBuffer.append("\\u");
              myOutBuffer.append((char)buf[0]);
              myOutBuffer.append((char)buf[1]);
              myOutBuffer.append((char)buf[2]);
              myOutBuffer.append((char)buf[3]);
            }
            else {
              myOutBuffer.append(decoded);
            }
          }
          else {
            myOutBuffer.append("\\");
            myOutBuffer.append((char)next);
          }
          start = in.position();
        }
      }
      decodeArray(in.array(), start, in.position());
    }
    catch (BufferUnderflowException e) {
      in.reset();
    }
    return doFlush(out);
  }

  private void decodeArray(byte @NotNull [] buf, int start, int end) {
    if (end <= start) return;
    ByteBuffer byteBuffer = ByteBuffer.wrap(buf, start, end-start);
    CharBuffer charBuffer = myBaseCharset.decode(byteBuffer);
    myOutBuffer.append(charBuffer);
  }

  private static char unicode(byte @NotNull [] ord) {
    int d1 = Character.digit((char)ord[0], 16);
    if (d1 == -1) return INVALID_CHAR;
    int d2 = Character.digit((char)ord[1], 16);
    if (d2 == -1) return INVALID_CHAR;
    int d3 = Character.digit((char)ord[2], 16);
    if (d3 == -1) return INVALID_CHAR;
    int d4 = Character.digit((char)ord[3], 16);
    if (d4 == -1) return INVALID_CHAR;
    int b1 = (d1 << 12) & 0xF000;
    int b2 = (d2 << 8) & 0x0F00;
    int b3 = (d3 << 4) & 0x00F0;
    int b4 = (d4) & 0x000F;
    int code = b1 | b2 | b3 | b4;
    if (Character.isWhitespace((char)code)) return INVALID_CHAR;
    return (char)code;
  }
}