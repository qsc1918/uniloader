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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;

import org.junit.jupiter.api.Test;

/** T2.4：入口点键名的双注册（照抄 QuiltLoaderImpl 第 1306-1307 行的行为）。 */
class QuiltEntrypointKeysTest {
	@Test
	void preLaunchIsAliasedBothWays() {
		Collection<String> fromQuilt = QuiltEntrypointKeys.aliases("pre_launch");
		assertTrue(fromQuilt.contains("pre_launch"));
		assertTrue(fromQuilt.contains("preLaunch"));

		Collection<String> fromFabric = QuiltEntrypointKeys.aliases("preLaunch");
		assertTrue(fromFabric.contains("pre_launch"));
		assertTrue(fromFabric.contains("preLaunch"));
	}

	@Test
	void otherKeysAreNotAliased() {
		// main/client/server 两边同名；init 是 QSL 自己按名字取的，加载器不该改写它
		assertEquals(1, QuiltEntrypointKeys.aliases("main").size());
		assertEquals(1, QuiltEntrypointKeys.aliases("client").size());
		assertEquals(1, QuiltEntrypointKeys.aliases("server").size());
		assertEquals(1, QuiltEntrypointKeys.aliases("init").size());
	}
}
