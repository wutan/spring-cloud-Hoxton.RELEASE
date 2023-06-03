/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.instrument.web;

import java.util.regex.Pattern;

import brave.http.HttpRequest;
import brave.sampler.SamplerFunction;

/**
 * Doesn't sample a span if skip pattern is matched.
 *
 * @author Marcin Grzejszczak
 * @since 2.0.0
 */
class SkipPatternHttpServerSampler implements SamplerFunction<HttpRequest> {

	private final SkipPatternProvider provider;

	private Pattern pattern;

	SkipPatternHttpServerSampler(SkipPatternProvider provider) {
		this.provider = provider;
	}

	@Override
	public Boolean trySample(HttpRequest request) {
		String url = request.path();
		boolean shouldSkip = pattern().matcher(url).matches();
		if (shouldSkip) {
			return false;
		}
		return null;
	}

	private Pattern pattern() {
		if (this.pattern == null) {
			this.pattern = this.provider.skipPattern();
		}
		return this.pattern;
	}

}
