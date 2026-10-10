/*
 * Copyright 2016 FabricMC
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

package net.fabricmc.loader.impl.compat.quilt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * T2.1：两份清单同时存在时的仲裁规则。
 *
 * <p>这个测试存在的意义是「钉住规则」：阶段 1 的实际行为是「谁先被扫到算谁」，
 * 那是巧合。现在规则写在一处，改规则必须连这个测试一起改。
 */
class QuiltManifestArbiterTest {
	@Test
	void quiltWinsWhenBothPresent() {
		assertEquals(QuiltManifestArbiter.Manifest.QUILT, QuiltManifestArbiter.choose(true, true));
	}

	@Test
	void fabricOnly() {
		assertEquals(QuiltManifestArbiter.Manifest.FABRIC, QuiltManifestArbiter.choose(true, false));
	}

	@Test
	void quiltOnly() {
		assertEquals(QuiltManifestArbiter.Manifest.QUILT, QuiltManifestArbiter.choose(false, true));
	}

	@Test
	void neither() {
		assertNull(QuiltManifestArbiter.choose(false, false));
	}

	@Test
	void conflictMessageExplainsTheRule() {
		String message = QuiltManifestArbiter.describeConflict("voicechat");
		assertTrue(message.contains("voicechat"));
		assertTrue(message.contains("fabric.mod.json"));
		assertTrue(message.contains("quilt.mod.json"));
		assertTrue(message.contains("Quilt"), "要说清楚为什么选 Quilt，而不只是说选了");
	}
}
