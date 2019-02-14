/**
 * Copyright (C) 2015-2019 Philip Helger (www.helger.com)
 * philip[at]helger[dot]com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.helger.as4.server.message;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.wss4j.common.ext.WSSecurityException;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import com.helger.as4.AS4TestConstants;
import com.helger.as4.CAS4;
import com.helger.as4.attachment.WSS4JAttachment;
import com.helger.as4.crypto.AS4CryptoFactory;
import com.helger.as4.crypto.ECryptoAlgorithmSign;
import com.helger.as4.crypto.ECryptoAlgorithmSignDigest;
import com.helger.as4.error.EEbmsError;
import com.helger.as4.messaging.domain.AS4ErrorMessage;
import com.helger.as4.messaging.domain.AS4ReceiptMessage;
import com.helger.as4.messaging.domain.AS4UserMessage;
import com.helger.as4.messaging.domain.MessageHelperMethods;
import com.helger.as4.messaging.sign.SignedMessageCreator;
import com.helger.as4.server.MockPModeGenerator;
import com.helger.as4.server.spi.MockMessageProcessorCheckingStreamsSPI;
import com.helger.as4.soap.ESOAPVersion;
import com.helger.as4.util.AS4ResourceManager;
import com.helger.as4lib.ebms3header.Ebms3CollaborationInfo;
import com.helger.as4lib.ebms3header.Ebms3Error;
import com.helger.as4lib.ebms3header.Ebms3MessageInfo;
import com.helger.as4lib.ebms3header.Ebms3MessageProperties;
import com.helger.as4lib.ebms3header.Ebms3PartyInfo;
import com.helger.as4lib.ebms3header.Ebms3PayloadInfo;
import com.helger.as4lib.ebms3header.Ebms3Property;
import com.helger.as4lib.ebms3header.Ebms3UserMessage;
import com.helger.commons.collection.impl.CommonsArrayList;
import com.helger.commons.collection.impl.ICommonsList;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public final class MockMessages
{
  private static final String DEFAULT_AGREEMENT = "urn:as4:agreements:so-that-we-have-a-non-empty-value";
  private static final String SOAP_12_PARTY_ID = "APP_000000000012";
  private static final String SOAP_11_PARTY_ID = "APP_000000000011";

  private MockMessages ()
  {}

  public static Document testSignedUserMessage (@Nonnull final ESOAPVersion eSOAPVersion,
                                                @Nullable final Node aPayload,
                                                @Nullable final ICommonsList <WSS4JAttachment> aAttachments,
                                                @Nonnull final AS4ResourceManager aResMgr) throws WSSecurityException
  {
    final AS4UserMessage aMsg = testUserMessageSoapNotSigned (eSOAPVersion, aPayload, aAttachments);
    final Document aSignedDoc = SignedMessageCreator.createSignedMessage (AS4CryptoFactory.DEFAULT_INSTANCE,
                                                                          aMsg.getAsSOAPDocument (aPayload),
                                                                          eSOAPVersion,
                                                                          aMsg.getMessagingID (),
                                                                          aAttachments,
                                                                          aResMgr,
                                                                          false,
                                                                          ECryptoAlgorithmSign.SIGN_ALGORITHM_DEFAULT,
                                                                          ECryptoAlgorithmSignDigest.SIGN_DIGEST_ALGORITHM_DEFAULT);
    return aSignedDoc;
  }

  public static Document testErrorMessage (@Nonnull final ESOAPVersion eSOAPVersion,
                                           @Nullable final ICommonsList <WSS4JAttachment> aAttachments,
                                           @Nonnull final AS4ResourceManager aResMgr) throws WSSecurityException
  {
    final ICommonsList <Ebms3Error> aEbms3ErrorList = new CommonsArrayList <> (EEbmsError.EBMS_INVALID_HEADER.getAsEbms3Error (Locale.US,
                                                                                                                               null));
    final AS4ErrorMessage aErrorMsg = AS4ErrorMessage.create (eSOAPVersion, aEbms3ErrorList).setMustUnderstand (true);
    final Document aSignedDoc = SignedMessageCreator.createSignedMessage (AS4CryptoFactory.DEFAULT_INSTANCE,
                                                                          aErrorMsg.getAsSOAPDocument (),
                                                                          eSOAPVersion,
                                                                          aErrorMsg.getMessagingID (),
                                                                          aAttachments,
                                                                          aResMgr,
                                                                          false,
                                                                          ECryptoAlgorithmSign.SIGN_ALGORITHM_DEFAULT,
                                                                          ECryptoAlgorithmSignDigest.SIGN_DIGEST_ALGORITHM_DEFAULT);
    return aSignedDoc;
  }

  public static AS4ReceiptMessage testReceiptMessage (@Nonnull final ESOAPVersion eSOAPVersion,
                                                      @Nullable final Ebms3UserMessage aEbms3UserMessage,
                                                      @Nullable final Document aUserMessage) throws DOMException
  {
    return AS4ReceiptMessage.create (eSOAPVersion,
                                     MessageHelperMethods.createRandomMessageID (),
                                     aEbms3UserMessage,
                                     aUserMessage,
                                     true)
                            .setMustUnderstand (true);
  }

  public static AS4UserMessage testUserMessageSoapNotSigned (@Nonnull final ESOAPVersion eSOAPVersion,
                                                             @Nullable final Node aPayload,
                                                             @Nullable final ICommonsList <WSS4JAttachment> aAttachments)
  {
    // Add properties
    final ICommonsList <Ebms3Property> aEbms3Properties = AS4TestConstants.getEBMSProperties ();

    final String sPModeID;

    final Ebms3MessageInfo aEbms3MessageInfo = MessageHelperMethods.createEbms3MessageInfo ();
    final Ebms3PayloadInfo aEbms3PayloadInfo = MessageHelperMethods.createEbms3PayloadInfo (aPayload != null,
                                                                                            aAttachments);

    final Ebms3CollaborationInfo aEbms3CollaborationInfo;
    final Ebms3PartyInfo aEbms3PartyInfo;
    if (eSOAPVersion.equals (ESOAPVersion.SOAP_11))
    {
      sPModeID = SOAP_11_PARTY_ID + "-" + SOAP_11_PARTY_ID;

      aEbms3CollaborationInfo = MessageHelperMethods.createEbms3CollaborationInfo (sPModeID,
                                                                                   DEFAULT_AGREEMENT,
                                                                                   AS4TestConstants.TEST_SERVICE_TYPE,
                                                                                   MockPModeGenerator.SOAP11_SERVICE,
                                                                                   AS4TestConstants.TEST_ACTION,
                                                                                   AS4TestConstants.TEST_CONVERSATION_ID);
      aEbms3PartyInfo = MessageHelperMethods.createEbms3PartyInfo (CAS4.DEFAULT_SENDER_URL,
                                                                   SOAP_11_PARTY_ID,
                                                                   CAS4.DEFAULT_RESPONDER_URL,
                                                                   SOAP_11_PARTY_ID);
    }
    else
    {
      sPModeID = SOAP_12_PARTY_ID + "-" + SOAP_12_PARTY_ID;

      aEbms3CollaborationInfo = MessageHelperMethods.createEbms3CollaborationInfo (sPModeID,
                                                                                   DEFAULT_AGREEMENT,
                                                                                   AS4TestConstants.TEST_SERVICE_TYPE,
                                                                                   AS4TestConstants.TEST_SERVICE,
                                                                                   AS4TestConstants.TEST_ACTION,
                                                                                   AS4TestConstants.TEST_CONVERSATION_ID);
      aEbms3PartyInfo = MessageHelperMethods.createEbms3PartyInfo (CAS4.DEFAULT_SENDER_URL,
                                                                   SOAP_12_PARTY_ID,
                                                                   CAS4.DEFAULT_RESPONDER_URL,
                                                                   SOAP_12_PARTY_ID);
    }

    final Ebms3MessageProperties aEbms3MessageProperties = MessageHelperMethods.createEbms3MessageProperties (aEbms3Properties);

    return AS4UserMessage.create (aEbms3MessageInfo,
                                  aEbms3PayloadInfo,
                                  aEbms3CollaborationInfo,
                                  aEbms3PartyInfo,
                                  aEbms3MessageProperties,
                                  eSOAPVersion)
                         .setMustUnderstand (true);
  }

  public static Document testUserMessageSoapNotSignedNotPModeConform (@Nonnull final ESOAPVersion eSOAPVersion,
                                                                      @Nullable final Node aPayload,
                                                                      @Nullable final ICommonsList <WSS4JAttachment> aAttachments)
  {
    // Add properties
    final ICommonsList <Ebms3Property> aEbms3Properties = AS4TestConstants.getEBMSProperties ();

    final String sPModeID = CAS4.DEFAULT_SENDER_URL + "-" + CAS4.DEFAULT_RESPONDER_URL;

    final Ebms3MessageInfo aEbms3MessageInfo = MessageHelperMethods.createEbms3MessageInfo ();
    final Ebms3PayloadInfo aEbms3PayloadInfo = MessageHelperMethods.createEbms3PayloadInfo (aPayload != null,
                                                                                            aAttachments);
    final Ebms3CollaborationInfo aEbms3CollaborationInfo = MessageHelperMethods.createEbms3CollaborationInfo (sPModeID +
                                                                                                              "x",
                                                                                                              DEFAULT_AGREEMENT,
                                                                                                              AS4TestConstants.TEST_SERVICE_TYPE,
                                                                                                              AS4TestConstants.TEST_SERVICE,
                                                                                                              MockMessageProcessorCheckingStreamsSPI.ACTION_FAILURE,
                                                                                                              AS4TestConstants.TEST_CONVERSATION_ID);
    final Ebms3PartyInfo aEbms3PartyInfo = MessageHelperMethods.createEbms3PartyInfo (CAS4.DEFAULT_SENDER_URL,
                                                                                      "testt",
                                                                                      CAS4.DEFAULT_RESPONDER_URL,
                                                                                      "testt");
    final Ebms3MessageProperties aEbms3MessageProperties = MessageHelperMethods.createEbms3MessageProperties (aEbms3Properties);

    final AS4UserMessage aDoc = AS4UserMessage.create (aEbms3MessageInfo,
                                                       aEbms3PayloadInfo,
                                                       aEbms3CollaborationInfo,
                                                       aEbms3PartyInfo,
                                                       aEbms3MessageProperties,
                                                       eSOAPVersion)
                                              .setMustUnderstand (true);
    return aDoc.getAsSOAPDocument (aPayload);
  }

  @Nullable
  @SuppressFBWarnings ("NP_NONNULL_PARAM_VIOLATION")
  public static Document emptyUserMessage (@Nonnull final ESOAPVersion eSOAPVersion,
                                           @Nullable final Node aPayload,
                                           @Nullable final ICommonsList <WSS4JAttachment> aAttachments)
  {
    // Add properties
    final ICommonsList <Ebms3Property> aEbms3Properties = new CommonsArrayList <> ();
    final Ebms3Property aEbms3PropertyProcess = new Ebms3Property ();
    aEbms3Properties.add (aEbms3PropertyProcess);

    // Use an empty message info by purpose
    final Ebms3MessageInfo aEbms3MessageInfo = MessageHelperMethods.createEbms3MessageInfo ();
    final Ebms3PayloadInfo aEbms3PayloadInfo = MessageHelperMethods.createEbms3PayloadInfo (aPayload != null,
                                                                                            aAttachments);
    final Ebms3CollaborationInfo aEbms3CollaborationInfo = MessageHelperMethods.createEbms3CollaborationInfo (null,
                                                                                                              null,
                                                                                                              null,
                                                                                                              "svc",
                                                                                                              "act",
                                                                                                              "conv");
    final Ebms3PartyInfo aEbms3PartyInfo = MessageHelperMethods.createEbms3PartyInfo ("fid", "frole", "tid", "trole");
    final Ebms3MessageProperties aEbms3MessageProperties = MessageHelperMethods.createEbms3MessageProperties (aEbms3Properties);

    final AS4UserMessage aDoc = AS4UserMessage.create (aEbms3MessageInfo,
                                                       aEbms3PayloadInfo,
                                                       aEbms3CollaborationInfo,
                                                       aEbms3PartyInfo,
                                                       aEbms3MessageProperties,
                                                       eSOAPVersion)
                                              .setMustUnderstand (true);
    return aDoc.getAsSOAPDocument (aPayload);
  }
}
