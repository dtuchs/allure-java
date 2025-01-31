/*
 *  Copyright 2016-2024 Qameta Software Inc
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.qameta.allure.restassured;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.google.common.collect.ImmutableList;
import io.qameta.allure.model.Attachment;
import io.qameta.allure.model.TestResult;
import io.qameta.allure.test.AllureResults;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.qameta.allure.test.RunUtils.runWithinTestContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class AttachmentArgumentProvider implements ArgumentsProvider {
    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {

        return Stream.of(
                arguments(ImmutableList.of("Request", "HTTP/1.1 200 OK"), new AllureRestAssured()),
                arguments(ImmutableList.of("Allure Request", "Allure Response"), new AllureRestAssured().setRequestAttachmentName("Allure Request").setResponseAttachmentName("Allure Response")),
                arguments(ImmutableList.of("Request", "Allure Response"), new AllureRestAssured().setResponseAttachmentName("Allure Response")),
                arguments(ImmutableList.of("Allure Request", "HTTP/1.1 200 OK"), new AllureRestAssured().setRequestAttachmentName("Allure Request"))
        );
    }
}

/**
 * @author charlie (Dmitry Baev).
 */
@SuppressWarnings("unchecked")
class AllureRestAssuredTest {

    @ParameterizedTest
    @ArgumentsSource(AttachmentArgumentProvider.class)
    void shouldCreateAttachment(final List<String> attachmentNames, final AllureRestAssured filter) {
        RestAssured.replaceFiltersWith(filter);
        final AllureResults results = execute();

        assertThat(results.getTestResults()
                .stream()
                .map(TestResult::getAttachments)
                .flatMap(Collection::stream)
                .map(Attachment::getName))
                .isEqualTo(attachmentNames);
    }

    @Test
    void shouldProperlySetAttachmentNameForSingleFilterInstance() {
        final AllureRestAssured filter = new AllureRestAssured();

        final ResponseDefinitionBuilder responseBuilderOne = WireMock.aResponse()
                .withStatus(200)
                .withBody("some body");

        final ResponseDefinitionBuilder responseBuilderTwo = WireMock.aResponse()
                .withStatus(400)
                .withBody("some other body");

        RestAssured.replaceFiltersWith(filter);
        final AllureResults resultsOne = executeWithStub(responseBuilderOne);

        RestAssured.replaceFiltersWith(filter);
        final AllureResults resultsTwo = executeWithStub(responseBuilderTwo);

        assertThat(resultsOne.getTestResults()
                .stream()
                .map(TestResult::getAttachments)
                .flatMap(Collection::stream)
                .map(Attachment::getName))
                .hasSize(2)
                .anyMatch(res -> res.equals("HTTP/1.1 200 OK"));

        assertThat(resultsTwo.getTestResults()
                .stream()
                .map(TestResult::getAttachments)
                .flatMap(Collection::stream)
                .map(Attachment::getName))
                .hasSize(2)
                .anyMatch(res -> res.equals("HTTP/1.1 400 Bad Request"));
    }

    @ParameterizedTest
    @ArgumentsSource(AttachmentArgumentProvider.class)
    void shouldCatchAttachmentBody(final List<String> attachmentNames, final AllureRestAssured filter) {
        RestAssured.replaceFiltersWith(filter);
        final AllureResults results = execute();

        List<Attachment> actualAttachments = results.getTestResults().stream()
                .map(TestResult::getAttachments)
                .flatMap(List::stream)
                .collect(Collectors.toList());

        assertThat(actualAttachments)
                .flatExtracting(Attachment::getName)
                .isEqualTo(attachmentNames)
                .doesNotContainNull();

        assertThat(actualAttachments)
                .flatExtracting(Attachment::getSource)
                .containsExactlyInAnyOrderElementsOf(new ArrayList<>(results.getAttachments().keySet()))
                .doesNotContainNull();
    }

    protected final AllureResults execute() {
        return executeWithStub(WireMock.aResponse().withBody("some body"));
    }

    protected final AllureResults executeWithStub(ResponseDefinitionBuilder responseBuilder) {
        final WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        final int statusCode = responseBuilder.build().getStatus();

        return runWithinTestContext(() -> {
            server.start();
            WireMock.configureFor(server.port());

            WireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/hello")).willReturn(responseBuilder));
            try {
                RestAssured.when().get(server.url("/hello")).then().statusCode(statusCode);
            } finally {
                server.stop();
                RestAssured.replaceFiltersWith(ImmutableList.of());
            }
        });
    }
}
