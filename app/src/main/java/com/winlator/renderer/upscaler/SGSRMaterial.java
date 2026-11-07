package com.winlator.renderer.upscaler;

import com.winlator.renderer.material.ScreenMaterial;

/**
 * Snapdragon(TM) Game Super Resolution 1 (SGSR): single-pass 12-tap Lanczos-like upscaler
 * with adaptive sharpening, optimized for Adreno GPUs.
 *
 * Fragment shader adapted from sgsr/v1/include/glsl/sgsr1_shader_mobile.frag of
 * https://github.com/SnapdragonStudios/snapdragon-gsr
 *
 * Copyright (c) 2023-2025, Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 *    conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 *    conditions and the following disclaimer in the documentation and/or other materials
 *    provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used
 *    to endorse or promote products derived from this software without specific prior
 *    written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR
 * TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
public class SGSRMaterial extends ScreenMaterial {
    public final Uniform viewportInfo = new Uniform("ViewportInfo");

    @Override
    protected String getGLSLVersion() {
        // textureGather() requires GLSL ES 3.10.
        return "310 es";
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision mediump float;",
            "precision highp int;",

            // RGBA mode (1): the green channel is used as the luma proxy.
            "#define OperationMode 1",
            "#define EdgeThreshold (8.0/255.0)",
            "#define EdgeSharpness 2.0",

            // xy = 1 / input size, zw = input size
            "uniform highp vec4 ViewportInfo;",
            "uniform mediump sampler2D screenTexture;",

            "in highp vec2 vUV;",
            "layout(location = 0) out vec4 outFragColor;",

            "float fastLanczos2(float x) {",
                "float wA = x - 4.0;",
                "float wB = x * wA - wA;",
                "wA *= wA;",
                "return wB * wA;",
            "}",

            "vec2 weightY(float dx, float dy, float c, float std) {",
                "float x = ((dx * dx) + (dy * dy)) * 0.55 + clamp(abs(c) * std, 0.0, 1.0);",
                "float w = fastLanczos2(x);",
                "return vec2(w, w * c);",
            "}",

            "void main() {",
                "const int mode = OperationMode;",
                "vec4 color;",
                "color.xyz = textureLod(screenTexture, vUV, 0.0).xyz;",

                "highp vec2 imgCoord = (vUV * ViewportInfo.zw) + vec2(-0.5, 0.5);",
                "highp vec2 imgCoordPixel = floor(imgCoord);",
                "highp vec2 coord = imgCoordPixel * ViewportInfo.xy;",
                "vec2 pl = imgCoord - imgCoordPixel;",
                "vec4 left = textureGather(screenTexture, coord, mode);",

                "float edgeVote = abs(left.z - left.y) + abs(color[mode] - left.y) + abs(color[mode] - left.z);",
                "if (edgeVote > EdgeThreshold) {",
                    "coord.x += ViewportInfo.x;",

                    "vec4 right = textureGather(screenTexture, coord + vec2(ViewportInfo.x, 0.0), mode);",
                    "vec4 upDown;",
                    "upDown.xy = textureGather(screenTexture, coord + vec2(0.0, -ViewportInfo.y), mode).wz;",
                    "upDown.zw = textureGather(screenTexture, coord + vec2(0.0, ViewportInfo.y), mode).yx;",

                    "float mean = (left.y + left.z + right.x + right.w) * 0.25;",
                    "left = left - vec4(mean);",
                    "right = right - vec4(mean);",
                    "upDown = upDown - vec4(mean);",
                    "color.w = color[mode] - mean;",

                    "float sum = (((abs(left.x) + abs(left.y)) + abs(left.z)) + abs(left.w)) +",
                               "(((abs(right.x) + abs(right.y)) + abs(right.z)) + abs(right.w)) +",
                               "(((abs(upDown.x) + abs(upDown.y)) + abs(upDown.z)) + abs(upDown.w));",
                    "float std = 2.181818 / sum;",

                    "vec2 aWY = weightY(pl.x, pl.y + 1.0, upDown.x, std);",
                    "aWY += weightY(pl.x - 1.0, pl.y + 1.0, upDown.y, std);",
                    "aWY += weightY(pl.x - 1.0, pl.y - 2.0, upDown.z, std);",
                    "aWY += weightY(pl.x, pl.y - 2.0, upDown.w, std);",
                    "aWY += weightY(pl.x + 1.0, pl.y - 1.0, left.x, std);",
                    "aWY += weightY(pl.x, pl.y - 1.0, left.y, std);",
                    "aWY += weightY(pl.x, pl.y, left.z, std);",
                    "aWY += weightY(pl.x + 1.0, pl.y, left.w, std);",
                    "aWY += weightY(pl.x - 1.0, pl.y - 1.0, right.x, std);",
                    "aWY += weightY(pl.x - 2.0, pl.y - 1.0, right.y, std);",
                    "aWY += weightY(pl.x - 2.0, pl.y, right.z, std);",
                    "aWY += weightY(pl.x - 1.0, pl.y, right.w, std);",

                    "float finalY = aWY.y / aWY.x;",
                    "float maxY = max(max(left.y, left.z), max(right.x, right.w));",
                    "float minY = min(min(left.y, left.z), min(right.x, right.w));",
                    "finalY = clamp(EdgeSharpness * finalY, minY, maxY);",

                    "float deltaY = finalY - color.w;",
                    // smooth high contrast input
                    "deltaY = clamp(deltaY, -23.0 / 255.0, 23.0 / 255.0);",

                    "color.xyz = clamp(color.xyz + vec3(deltaY), 0.0, 1.0);",
                "}",

                "color.w = 1.0;",
                "outFragColor = color;",
            "}"
        );
    }
}
