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

import org.junit.jupiter.api.Test;

/**
 * T2.1: the JSON5 trailing-comma stripper.
 *
 * <p>为什么单独给它一份测试：它是整个解析链里唯一「逐字符扫」的地方，
 * 也是最容易悄悄写错的地方——把字符串里的逗号当成语法逗号删掉，
 * 症状会是一个内容莫名少了一段字符串的模组，而解析本身「成功」。
 */
class QuiltJson5Test {
	private static String strip(String s) {
		return QuiltJson5.stripTrailingCommas(s);
	}

	@Test
	void removesTrailingCommaInObject() {
		assertEquals("{\"a\":1}", strip("{\"a\":1,}"));
		assertEquals("{\"a\":1  }", strip("{\"a\":1 , }"), "逗号与 } 之间有空白也要删");
	}

	@Test
	void removesTrailingCommaInArray() {
		assertEquals("[1,2]", strip("[1,2,]"));
		assertEquals("[1,2 ]", strip("[1,2, ]"), "逗号与 ] 之间有空白也要删");
	}

	@Test
	void removesTrailingCommaAcrossNewlinesAndComments() {
		assertEquals("{\"a\":1\n// c\n}", strip("{\"a\":1,\n// c\n}"));
		assertEquals("{\"a\":1\n/* c */}", strip("{\"a\":1,\n/* c */}"));
	}

	@Test
	void keepsCommaInsideString() {
		// 字符串里的逗号后面跟着 } 也不能删——这是最容易写错的一类
		assertEquals("{\"a\":\",}\"}", strip("{\"a\":\",}\"}"));
		assertEquals("{\"a\":\",\"}", strip("{\"a\":\",\"}"));
		assertEquals("{\"a\":\"x,\"}", strip("{\"a\":\"x,\"}"));
	}

	@Test
	void keepsCommaInsideComment() {
		assertEquals("{\"a\":1 // ,\n}", strip("{\"a\":1 // ,\n}"));
		assertEquals("{\"a\":1 /* , */}", strip("{\"a\":1 /* , */}"));
	}

	@Test
	void handlesEscapedQuoteInString() {
		assertEquals("{\"a\":\"\\\",}\"}", strip("{\"a\":\"\\\",}\"}"));
	}

	@Test
	void leavesValidJsonUntouched() {
		String json = "{\"a\":[1,2],\"b\":{\"c\":\"d\"}}";
		assertEquals(json, strip(json));
	}

	@Test
	void keepsCommaBetweenRealElements() {
		assertEquals("[1,2,3]", strip("[1,2,3]"));
		assertEquals("{\"a\":1,\"b\":2}", strip("{\"a\":1,\"b\":2}"));
	}
}
