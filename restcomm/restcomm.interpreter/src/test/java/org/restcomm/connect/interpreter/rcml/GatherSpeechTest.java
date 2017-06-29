/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2014, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 */

package org.restcomm.connect.interpreter.rcml;

import akka.actor.Actor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActorFactory;
import akka.testkit.JavaTestKit;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.http.NameValuePair;
import org.joda.time.DateTime;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;
import org.restcomm.connect.commons.cache.DiskCacheRequest;
import org.restcomm.connect.commons.cache.DiskCacheResponse;
import org.restcomm.connect.commons.configuration.RestcommConfiguration;
import org.restcomm.connect.commons.dao.CollectedResult;
import org.restcomm.connect.commons.dao.Sid;
import org.restcomm.connect.commons.patterns.Observe;
import org.restcomm.connect.commons.telephony.CreateCallType;
import org.restcomm.connect.dao.CallDetailRecordsDao;
import org.restcomm.connect.dao.DaoManager;
import org.restcomm.connect.http.client.DownloaderResponse;
import org.restcomm.connect.http.client.HttpRequestDescriptor;
import org.restcomm.connect.http.client.HttpResponseDescriptor;
import org.restcomm.connect.interpreter.StartInterpreter;
import org.restcomm.connect.interpreter.VoiceInterpreter;
import org.restcomm.connect.interpreter.rcml.domain.GatherAttributes;
import org.restcomm.connect.mscontrol.api.messages.Collect;
import org.restcomm.connect.mscontrol.api.messages.MediaGroupResponse;
import org.restcomm.connect.mscontrol.api.messages.Play;
import org.restcomm.connect.telephony.api.CallInfo;
import org.restcomm.connect.telephony.api.CallResponse;
import org.restcomm.connect.telephony.api.CallStateChanged;
import org.restcomm.connect.telephony.api.GetCallInfo;
import org.restcomm.connect.telephony.api.Hangup;

import java.net.URI;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Created by gdubina on 6/24/17.
 */
public class GatherSpeechTest {

    private static ActorSystem system;

    private Configuration configuration;

    private URI requestUri = URI.create("http://127.0.0.1/gather.xml");
    private URI playUri = URI.create("http://127.0.0.1/play.wav");
    private URI actionCallbackUri = URI.create("http://127.0.0.1/gather-action.xml");
    private URI partialCallbackUri = URI.create("http://127.0.0.1/gather-partial.xml");

    private String endRcml = "<Response><Hangup/></Response>";
    private String playRcml = "<Response><Play>" + playUri + "</Play></Response>";
    private String gatherRcml = "<Response><Gather " +
            GatherAttributes.ATTRIBUTE_INPUT + "=\"speech\" " +
            GatherAttributes.ATTRIBUTE_ACTION + "=\"" + actionCallbackUri + "\" " +
            GatherAttributes.ATTRIBUTE_PARTIAL_RESULT_CALLBACK + "=\"" + partialCallbackUri + "\" " +
            GatherAttributes.ATTRIBUTE_NUM_DIGITS + "=\"1\" " +
            GatherAttributes.ATTRIBUTE_TIME_OUT + "=\"60\">" +
            "</Gather></Response>";
    private String gatherEmpty = "<Response><Gather " +
            GatherAttributes.ATTRIBUTE_PARTIAL_RESULT_CALLBACK + "=\"" + partialCallbackUri + "\">" +
            "</Gather></Response>";

    public GatherSpeechTest() {
        super();
    }

    @BeforeClass
    public static void before() throws Exception {
        system = ActorSystem.create();
    }

    @AfterClass
    public static void after() throws Exception {
        system.shutdown();
    }

    @Before
    public void init() {
        String restcommXmlPath = this.getClass().getResource("/restcomm.xml").getFile();
        try {
            configuration = getConfiguration(restcommXmlPath);
            RestcommConfiguration.createOnce(configuration);
        } catch (ConfigurationException e) {
            throw new RuntimeException();
        }
    }

    private Configuration getConfiguration(String path) throws ConfigurationException {
        XMLConfiguration xmlConfiguration = new XMLConfiguration();
        xmlConfiguration.setDelimiterParsingDisabled(true);
        xmlConfiguration.setAttributeSplittingDisabled(true);
        xmlConfiguration.load(path);

        return xmlConfiguration;
    }

    private HttpResponseDescriptor getOkRcml(URI uri, String rcml) {
        HttpResponseDescriptor.Builder builder = HttpResponseDescriptor.builder();
        builder.setURI(uri);
        builder.setStatusCode(200);
        builder.setStatusDescription("OK");
        builder.setContent(rcml);
        builder.setContentLength(rcml.length());
        builder.setContentType("text/xml");
        return builder.build();
    }

    private ActorRef createVoiceInterpreter(final ActorRef observer) {
        //dao
        final CallDetailRecordsDao recordsDao = Mockito.mock(CallDetailRecordsDao.class);
        Mockito.when(recordsDao.getCallDetailRecord(Mockito.any(Sid.class))).thenReturn(null);

        final DaoManager storage = Mockito.mock(DaoManager.class);
        Mockito.when(storage.getCallDetailRecordsDao()).thenReturn(recordsDao);

        //actors
        final ActorRef downloader = new MockedActor("downloader")
                .add(DiskCacheRequest.class, new DiskCacheRequestProperty(playUri), new DiskCacheResponse(playUri))
                .asRef(system);

        final ActorRef callManager = new MockedActor("callManager").asRef(system);

        final Props props = new Props(new UntypedActorFactory() {
            private static final long serialVersionUID = 1L;

            @Override
            public Actor create() throws Exception {
                return new VoiceInterpreter(configuration,
                        new Sid("ACae6e420f425248d6a26948c17a9e2acf"),
                        null,
                        "2012-04-24",
                        requestUri, "GET",
                        null, null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        callManager,
                        null,
                        null,
                        null,
                        storage,
                        null,
                        null,
                        false, null, null) {
                    @Override
                    protected ActorRef downloader() {
                        return observer;
                    }

                    @Override
                    protected ActorRef cache(String path, String uri) {
                        return downloader;
                    }

                    @Override
                    public ActorRef getCache() {
                        return downloader;
                    }
                };
            }
        });
        return system.actorOf(props);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testPartialHangupScenario() throws Exception {
        new JavaTestKit(system) {
            {
                final ActorRef observer = getRef();
                final ActorRef interpreter = createVoiceInterpreter(observer);
                interpreter.tell(new StartInterpreter(observer), observer);

                expectMsgClass(GetCallInfo.class);
                interpreter.tell(new CallResponse(new CallInfo(
                        new Sid("ACae6e420f425248d6a26948c17a9e2acf"),
                        CallStateChanged.State.IN_PROGRESS,
                        CreateCallType.SIP,
                        "inbound",
                        new DateTime(),
                        null,
                        "test", "test",
                        "testTo",
                        null,
                        null,
                        false,
                        false,
                        false,
                        new DateTime())), observer);

                expectMsgClass(Observe.class);

                //wait for rcml downloading
                HttpRequestDescriptor callback = expectMsgClass(HttpRequestDescriptor.class);
                assertEquals(callback.getUri(), requestUri);

                interpreter.tell(new DownloaderResponse(getOkRcml(requestUri, gatherRcml)), observer);

                expectMsgClass(Collect.class);

                //generate partial response1
                interpreter.tell(new MediaGroupResponse(new CollectedResult("1", true, true)), observer);

                callback = expectMsgClass(HttpRequestDescriptor.class);
                assertEquals(callback.getUri(), partialCallbackUri);
                assertEquals(findParam(callback.getParameters(), "Speech").getValue(), "1");

                interpreter.tell(new DownloaderResponse(getOkRcml(partialCallbackUri, "")), observer);

                //generate partial response2
                interpreter.tell(new MediaGroupResponse(new CollectedResult("12", true, true)), observer);

                callback = expectMsgClass(HttpRequestDescriptor.class);
                assertEquals(callback.getUri(), partialCallbackUri);
                assertEquals(findParam(callback.getParameters(), "Speech").getValue(), "12");

                interpreter.tell(new DownloaderResponse(getOkRcml(partialCallbackUri, endRcml)), observer);

                expectMsgClass(Hangup.class);
            }
        };
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testPartialAndPlayScenario() throws Exception {
        new JavaTestKit(system) {
            {
                final ActorRef observer = getRef();
                final ActorRef interpreter = createVoiceInterpreter(observer);
                interpreter.tell(new StartInterpreter(observer), observer);

                expectMsgClass(GetCallInfo.class);
                interpreter.tell(new CallResponse(new CallInfo(
                        new Sid("ACae6e420f425248d6a26948c17a9e2acf"),
                        CallStateChanged.State.IN_PROGRESS,
                        CreateCallType.SIP,
                        "inbound",
                        new DateTime(),
                        null,
                        "test", "test",
                        "testTo",
                        null,
                        null,
                        false,
                        false,
                        false,
                        new DateTime())), observer);

                expectMsgClass(Observe.class);

                //wait for rcml downloading
                HttpRequestDescriptor callback = expectMsgClass(HttpRequestDescriptor.class);
                assertEquals(callback.getUri(), requestUri);

                interpreter.tell(new DownloaderResponse(getOkRcml(requestUri, gatherRcml)), observer);

                expectMsgClass(Collect.class);

                //generate partial response1
                interpreter.tell(new MediaGroupResponse(new CollectedResult("1", true, true)), observer);

                callback = expectMsgClass(HttpRequestDescriptor.class);
                assertEquals(callback.getUri(), partialCallbackUri);
                assertEquals(findParam(callback.getParameters(), "Speech").getValue(), "1");

                interpreter.tell(new DownloaderResponse(getOkRcml(partialCallbackUri, "")), observer);

                //generate partial response2
                interpreter.tell(new MediaGroupResponse(new CollectedResult("12", true, true)), observer);

                callback = expectMsgClass(HttpRequestDescriptor.class);
                assertEquals(callback.getUri(), partialCallbackUri);
                assertEquals(findParam(callback.getParameters(), "Speech").getValue(), "12");

                interpreter.tell(new DownloaderResponse(getOkRcml(partialCallbackUri, playRcml)), observer);

                //wait for new tag: Play
                expectMsgClass(Play.class);

                //simulate play is finished
                interpreter.tell(new MediaGroupResponse(new CollectedResult("", false, false)), observer);

                expectMsgClass(Hangup.class);
            }
        };
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testValidateDefaultAttributeValues() throws Exception {
        new JavaTestKit(system) {
            {
                final ActorRef observer = getRef();
                final ActorRef interpreter = createVoiceInterpreter(observer);
                interpreter.tell(new StartInterpreter(observer), observer);

                expectMsgClass(GetCallInfo.class);
                interpreter.tell(new CallResponse(new CallInfo(
                        new Sid("ACae6e420f425248d6a26948c17a9e2acf"),
                        CallStateChanged.State.IN_PROGRESS,
                        CreateCallType.SIP,
                        "inbound",
                        new DateTime(),
                        null,
                        "test", "test",
                        "testTo",
                        null,
                        null,
                        false,
                        false,
                        false,
                        new DateTime())), observer);

                expectMsgClass(Observe.class);

                //wait for rcml downloading
                HttpRequestDescriptor callback = expectMsgClass(HttpRequestDescriptor.class);
                assertEquals(callback.getUri(), requestUri);

                interpreter.tell(new DownloaderResponse(getOkRcml(requestUri, gatherEmpty)), observer);
                Collect collect = expectMsgClass(Collect.class);
                assertEquals(Collect.Type.DTMF, collect.type());
                assertEquals("en-US", collect.lang());
                assertEquals("#", collect.endInputKey());
                assertSame(5, collect.timeout());
                assertEquals("google", collect.getDriver());
            }
        };
    }

    private NameValuePair findParam(final List<NameValuePair> params, final String key) {
        return Iterables.find(params, new Predicate<NameValuePair>() {
            public boolean apply(NameValuePair p) {
                return key.equals(p.getName());
            }
        });
    }

    public static class DiskCacheRequestProperty extends MockedActor.SimplePropertyPredicate<DiskCacheRequest, URI> {

        static Function<DiskCacheRequest, URI> extractor = new Function<DiskCacheRequest, URI>() {
            @Override
            public URI apply(DiskCacheRequest diskCacheRequest) {
                return diskCacheRequest.uri();
            }
        };

        public DiskCacheRequestProperty(URI value) {
            super(value, extractor);
        }
    }
}