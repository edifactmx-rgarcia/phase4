/**
 * Copyright (C) 2015-2020 Philip Helger (www.helger.com)
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
package com.helger.phase4.servlet.soap;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.xml.namespace.QName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.helger.commons.ValueEnforcer;
import com.helger.commons.charset.CharsetHelper;
import com.helger.commons.collection.CollectionHelper;
import com.helger.commons.collection.impl.CommonsHashMap;
import com.helger.commons.collection.impl.ICommonsList;
import com.helger.commons.collection.impl.ICommonsMap;
import com.helger.commons.equals.EqualsHelper;
import com.helger.commons.error.IError;
import com.helger.commons.error.SingleError;
import com.helger.commons.error.list.ErrorList;
import com.helger.commons.state.ESuccess;
import com.helger.commons.string.StringHelper;
import com.helger.jaxb.validation.CollectingValidationEventHandler;
import com.helger.phase4.attachment.EAS4CompressionMode;
import com.helger.phase4.attachment.WSS4JAttachment;
import com.helger.phase4.ebms3header.Ebms3CollaborationInfo;
import com.helger.phase4.ebms3header.Ebms3Error;
import com.helger.phase4.ebms3header.Ebms3Messaging;
import com.helger.phase4.ebms3header.Ebms3PartInfo;
import com.helger.phase4.ebms3header.Ebms3PartyId;
import com.helger.phase4.ebms3header.Ebms3PayloadInfo;
import com.helger.phase4.ebms3header.Ebms3Property;
import com.helger.phase4.ebms3header.Ebms3PullRequest;
import com.helger.phase4.ebms3header.Ebms3Receipt;
import com.helger.phase4.ebms3header.Ebms3SignalMessage;
import com.helger.phase4.ebms3header.Ebms3UserMessage;
import com.helger.phase4.error.EEbmsError;
import com.helger.phase4.marshaller.Ebms3ReaderBuilder;
import com.helger.phase4.messaging.domain.MessageHelperMethods;
import com.helger.phase4.mgr.MetaAS4Manager;
import com.helger.phase4.model.mpc.IMPC;
import com.helger.phase4.model.mpc.IMPCManager;
import com.helger.phase4.model.pmode.IPMode;
import com.helger.phase4.model.pmode.leg.PModeLeg;
import com.helger.phase4.model.pmode.resolve.IPModeResolver;
import com.helger.phase4.servlet.AS4MessageState;
import com.helger.phase4.servlet.mgr.AS4ServerConfiguration;
import com.helger.phase4.servlet.mgr.AS4ServletPullRequestProcessorManager;
import com.helger.phase4.servlet.spi.IAS4ServletPullRequestProcessorSPI;
import com.helger.xml.XMLHelper;

/**
 * This class manages the EBMS Messaging SOAP header element
 *
 * @author Philip Helger
 * @author bayerlma
 */
public class SOAPHeaderElementProcessorExtractEbms3Messaging implements ISOAPHeaderElementProcessor
{
  /** The QName for which this processor should be invoked */
  public static final QName QNAME_MESSAGING = new QName ("http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/", "Messaging");

  private static final Logger LOGGER = LoggerFactory.getLogger (SOAPHeaderElementProcessorExtractEbms3Messaging.class);

  private final IPModeResolver m_aPModeResolver;

  public SOAPHeaderElementProcessorExtractEbms3Messaging (@Nonnull final IPModeResolver aPModeResolver)
  {
    ValueEnforcer.notNull (aPModeResolver, "PModeResolver");
    m_aPModeResolver = aPModeResolver;
  }

  /**
   * Checks if Leg1 should be used or not.
   *
   * @param aUserMessage
   *        needed to get the message ids
   * @return true if leg1 should be used else false
   */
  private static boolean _isUseLeg1 (@Nonnull final Ebms3UserMessage aUserMessage)
  {
    final String sThisMessageID = aUserMessage.getMessageInfo ().getMessageId ();
    final String sRefToMessageID = aUserMessage.getMessageInfo ().getRefToMessageId ();

    if (StringHelper.hasText (sRefToMessageID))
      if (sThisMessageID.equals (sRefToMessageID))
        LOGGER.warn ("MessageID and ReferenceToMessageID are the same (" + sThisMessageID + ")!");

    // If the message has a non-empty reference to a previous message, and this
    // reference differs from this message's ID, than leg 2 should be used
    return StringHelper.hasNoText (sRefToMessageID) || sRefToMessageID.equals (sThisMessageID);
  }

  /**
   * Determines the effective MPCID.
   *
   * @param aUserMessage
   *        to get the MPC that is used
   * @param aPModeLeg
   *        to get the MPC that will be used if the message does not define one
   * @return the MPCID
   */
  @Nullable
  private static String _getMPCIDOfUserMsg (@Nonnull final Ebms3UserMessage aUserMessage, @Nonnull final PModeLeg aPModeLeg)
  {
    String sEffectiveMPCID = aUserMessage.getMpc ();
    if (sEffectiveMPCID == null)
    {
      if (aPModeLeg.getBusinessInfo () != null)
        sEffectiveMPCID = aPModeLeg.getBusinessInfo ().getMPCID ();
    }
    return sEffectiveMPCID;
  }

  /**
   * Checks if the MPC that is contained in the PMode is valid.
   *
   * @param aPModeLeg
   *        the leg to get the MPCID
   * @param aMPCMgr
   *        the MPC-Manager to search for the MPCID in the persisted data
   * @param aLocale
   *        Locale to be used for the error messages
   * @param aErrorList
   *        to write errors to if they occur
   * @return Success if everything is all right, else Failure
   */
  @Nonnull
  private static ESuccess _checkMPCOfPMode (@Nonnull final PModeLeg aPModeLeg,
                                            @Nonnull final IMPCManager aMPCMgr,
                                            @Nonnull final Locale aLocale,
                                            @Nonnull final ErrorList aErrorList)
  {
    // Check if MPC is contained in PMode and if so, if it is valid
    if (aPModeLeg.getBusinessInfo () != null)
    {
      final String sPModeMPC = aPModeLeg.getBusinessInfo ().getMPCID ();
      if (sPModeMPC != null && !aMPCMgr.containsWithID (sPModeMPC))
      {
        LOGGER.error ("Error processing the usermessage, PMode-MPC ID '" + sPModeMPC + "' is invalid!");

        aErrorList.add (EEbmsError.EBMS_PROCESSING_MODE_MISMATCH.getAsError (aLocale));
        return ESuccess.FAILURE;
      }
    }
    return ESuccess.SUCCESS;
  }

  /**
   * Checks if the Document has a SOAPBodyPayload.
   *
   * @param aPModeLeg
   *        to get the SOAPVersion
   * @param aSOAPDoc
   *        the document that should be checked if it contains a SOAPBodyPayload
   * @return true if it contains a SOAPBodyPayload else false
   */
  private static boolean _checkSOAPBodyHasPayload (@Nonnull final PModeLeg aPModeLeg, @Nonnull final Document aSOAPDoc)
  {
    // Check if a SOAPBodyPayload exists
    final Element aBody = XMLHelper.getFirstChildElementOfName (aSOAPDoc.getFirstChild (),
                                                                aPModeLeg.getProtocol ().getSoapVersion ().getBodyElementName ());
    return aBody != null && aBody.hasChildNodes ();
  }

  @Nonnull
  public ESuccess processHeaderElement (@Nonnull final Document aSOAPDoc,
                                        @Nonnull final Element aElement,
                                        @Nonnull final ICommonsList <WSS4JAttachment> aAttachments,
                                        @Nonnull final AS4MessageState aState,
                                        @Nonnull final ErrorList aErrorList)
  {
    final IMPCManager aMPCMgr = MetaAS4Manager.getMPCMgr ();
    IPMode aPMode = null;
    final ICommonsMap <String, EAS4CompressionMode> aCompressionAttachmentIDs = new CommonsHashMap <> ();
    IMPC aEffectiveMPC = null;
    String sInitiatorID = null;
    String sResponderID = null;
    final Locale aLocale = aState.getLocale ();

    // Parse EBMS3 Messaging object
    final CollectingValidationEventHandler aCVEH = new CollectingValidationEventHandler ();
    final Ebms3Messaging aMessaging = Ebms3ReaderBuilder.ebms3Messaging ().setValidationEventHandler (aCVEH).read (aElement);

    // If the ebms3reader above fails aMessageing will be null => invalid/not
    // wellformed
    if (aMessaging == null)
    {
      // Errorcode/Id would be null => not conform with Ebms3ErrorMessage since
      // the message always needs a errorcode =>
      // Invalid Header == not wellformed/invalid xml
      for (final IError aError : aCVEH.getErrorList ())
      {
        aErrorList.add (SingleError.builder (aError).setErrorID (EEbmsError.EBMS_INVALID_HEADER.getErrorCode ()).build ());
      }
      return ESuccess.FAILURE;
    }

    // Remember in state
    aState.setMessaging (aMessaging);

    // 0 or 1 are allowed
    final int nUserMessages = aMessaging.getUserMessageCount ();
    if (nUserMessages > 1)
    {
      LOGGER.error ("Too many UserMessage objects (" + nUserMessages + ") contained.");

      aErrorList.add (EEbmsError.EBMS_VALUE_INCONSISTENT.getAsError (aLocale));
      return ESuccess.FAILURE;
    }

    // 0 or 1 are allowed
    final int nSignalMessages = aMessaging.getSignalMessageCount ();
    if (nSignalMessages > 1)
    {
      LOGGER.error ("Too many SignalMessage objects (" + nSignalMessages + ") contained.");

      aErrorList.add (EEbmsError.EBMS_VALUE_INCONSISTENT.getAsError (aLocale));
      return ESuccess.FAILURE;
    }

    if (nUserMessages + nSignalMessages == 0)
    {
      // No Message was found
      LOGGER.error ("Neither UserMessage nor SignalMessage object contained.");
      aErrorList.add (EEbmsError.EBMS_VALUE_INCONSISTENT.getAsError (aLocale));
      return ESuccess.FAILURE;
    }

    // Check if the usermessage has a PMode in the collaboration info
    final Ebms3UserMessage aUserMessage = CollectionHelper.getAtIndex (aMessaging.getUserMessage (), 0);
    if (aUserMessage != null)
    {
      final List <Ebms3PartyId> aFromPartyIdList = aUserMessage.getPartyInfo ().getFrom ().getPartyId ();
      final List <Ebms3PartyId> aToPartyIdList = aUserMessage.getPartyInfo ().getTo ().getPartyId ();

      if (aFromPartyIdList.size () > 1 || aToPartyIdList.size () > 1)
      {
        LOGGER.error ("More than one partyId is containted in From or To Recipient please check the message.");
        aErrorList.add (EEbmsError.EBMS_VALUE_INCONSISTENT.getAsError (aLocale));
        return ESuccess.FAILURE;
      }

      // Setting Initiator and Responder id, Required values or else xsd will
      // throw an error
      sInitiatorID = aFromPartyIdList.get (0).getValue ();
      sResponderID = aToPartyIdList.get (0).getValue ();

      final Ebms3CollaborationInfo aCollaborationInfo = aUserMessage.getCollaborationInfo ();
      if (aCollaborationInfo != null)
      {
        // Find PMode
        String sPModeID = null;
        if (aCollaborationInfo.getAgreementRef () != null)
          sPModeID = aCollaborationInfo.getAgreementRef ().getPmode ();

        // Get responder address from properties file (may be null)
        final String sResponderAddress = AS4ServerConfiguration.getServerAddress ();

        aPMode = m_aPModeResolver.getPModeOfID (sPModeID,
                                                aCollaborationInfo.getService ().getValue (),
                                                aCollaborationInfo.getAction (),
                                                sInitiatorID,
                                                sResponderID,
                                                sResponderAddress);

        // Should be screened by the xsd conversion already
        if (aPMode == null)
        {
          LOGGER.error ("Failed to resolve PMode '" + sPModeID + "' using resolver " + m_aPModeResolver);

          aErrorList.add (EEbmsError.EBMS_PROCESSING_MODE_MISMATCH.getAsError (aLocale));
          return ESuccess.FAILURE;
        }
      }

      // to use the configuration for leg2
      PModeLeg aPModeLeg1 = null;
      PModeLeg aPModeLeg2 = null;

      // Needed for the compression check: it is not allowed to have a
      // compressed attachment and a SOAPBodyPayload
      boolean bHasSoapBodyPayload = false;

      if (aPMode != null)
      {
        aPModeLeg1 = aPMode.getLeg1 ();
        aPModeLeg2 = aPMode.getLeg2 ();

        // if the two - way is selected, check if it requires two legs and if
        // both are present
        if (aPMode.getMEPBinding ().getRequiredLegs () == 2 && aPModeLeg2 == null)
        {
          LOGGER.error ("Error processing the UserMessage, PMode does not contain leg 2.");
          aErrorList.add (EEbmsError.EBMS_PROCESSING_MODE_MISMATCH.getAsError (aLocale));
          return ESuccess.FAILURE;
        }

        final boolean bUseLeg1 = _isUseLeg1 (aUserMessage);
        final PModeLeg aEffectiveLeg = bUseLeg1 ? aPModeLeg1 : aPModeLeg2;
        final int nLegNum = bUseLeg1 ? 1 : 2;
        if (aEffectiveLeg == null)
        {
          LOGGER.error ("Error processing the UserMessage, PMode does not contain effective leg " + nLegNum + ".");
          aErrorList.add (EEbmsError.EBMS_PROCESSING_MODE_MISMATCH.getAsError (aLocale));
          return ESuccess.FAILURE;
        }

        aState.setEffectivePModeLeg (nLegNum, aEffectiveLeg);
        if (_checkMPCOfPMode (aEffectiveLeg, aMPCMgr, aLocale, aErrorList).isFailure ())
          return ESuccess.FAILURE;

        bHasSoapBodyPayload = _checkSOAPBodyHasPayload (aEffectiveLeg, aSOAPDoc);
        final String sEffectiveMPCID = _getMPCIDOfUserMsg (aUserMessage, aEffectiveLeg);

        // PMode is valid
        // Now Check if MPC valid
        aEffectiveMPC = aMPCMgr.getMPCOrDefaultOfID (sEffectiveMPCID);
        if (aEffectiveMPC == null)
        {
          LOGGER.error ("Error processing the UserMessage, effective MPC ID '" + sEffectiveMPCID + "' is unknown!");

          aErrorList.add (EEbmsError.EBMS_VALUE_INCONSISTENT.getAsError (aLocale));
          return ESuccess.FAILURE;
        }
      }

      // Remember in state
      aState.setSoapBodyPayloadPresent (bHasSoapBodyPayload);

      final Ebms3PayloadInfo aEbms3PayloadInfo = aUserMessage.getPayloadInfo ();
      if (aEbms3PayloadInfo == null || aEbms3PayloadInfo.getPartInfo ().isEmpty ())
      {
        if (bHasSoapBodyPayload)
        {
          LOGGER.error ("No PartInfo is specified, so no SOAP body payload is allowed.");

          aErrorList.add (EEbmsError.EBMS_VALUE_INCONSISTENT.getAsError (aLocale));
          return ESuccess.FAILURE;
        }

        // For the case that there is no Payload/Part - Info but still
        // attachments in the message
        if (aAttachments.isNotEmpty ())
        {
          LOGGER.error ("No PartInfo is specified, so no attachments are allowed.");

          aErrorList.add (EEbmsError.EBMS_EXTERNAL_PAYLOAD_ERROR.getAsError (aLocale));
          return ESuccess.FAILURE;
        }
      }
      else
      {
        // Check if there are more Attachments then specified
        if (aAttachments.size () > aEbms3PayloadInfo.getPartInfoCount ())
        {
          LOGGER.error ("Error processing the UserMessage, the amount of specified attachments does not correlate with the actual attachments in the UserMessage. Expected " +
                        aEbms3PayloadInfo.getPartInfoCount () +
                        " but having " +
                        aAttachments.size () +
                        " attachments.");

          aErrorList.add (EEbmsError.EBMS_EXTERNAL_PAYLOAD_ERROR.getAsError (aLocale));
          return ESuccess.FAILURE;
        }

        int nSpecifiedAttachments = 0;

        for (final Ebms3PartInfo aPartInfo : aEbms3PayloadInfo.getPartInfo ())
        {
          // If href is null or empty there has to be a SOAP Payload
          if (StringHelper.hasNoText (aPartInfo.getHref ()))
          {
            // Check if there is a BodyPayload as specified in the UserMessage
            if (!bHasSoapBodyPayload)
            {
              LOGGER.error ("Error processing the UserMessage, Expected a BodyPayload but there is none present. ");

              aErrorList.add (EEbmsError.EBMS_VALUE_INCONSISTENT.getAsError (aLocale));
              return ESuccess.FAILURE;
            }
          }
          else
          {
            // Attachment
            // To check attachments which are specified in the usermessage and
            // the real amount in the mime message
            nSpecifiedAttachments++;

            final String sAttachmentID = StringHelper.trimStart (aPartInfo.getHref (), MessageHelperMethods.PREFIX_CID);
            final WSS4JAttachment aIncomingAttachment = aAttachments.findFirst (x -> EqualsHelper.equals (x.getId (), sAttachmentID));
            if (aIncomingAttachment == null)
              LOGGER.warn ("Failed to resolve MIME attachment '" +
                           sAttachmentID +
                           "' in list of " +
                           aAttachments.getAllMapped (WSS4JAttachment::getId));

            boolean bMimeTypePresent = false;
            boolean bCompressionTypePresent = false;

            if (aPartInfo.getPartProperties () != null)
              for (final Ebms3Property aEbms3Property : aPartInfo.getPartProperties ().getProperty ())
              {
                final String sPropertyName = aEbms3Property.getName ();
                final String sPropertyValue = aEbms3Property.getValue ();

                if (sPropertyName.equalsIgnoreCase (MessageHelperMethods.PART_PROPERTY_MIME_TYPE))
                {
                  bMimeTypePresent = StringHelper.hasText (sPropertyValue);
                }
                else
                  if (sPropertyName.equalsIgnoreCase (MessageHelperMethods.PART_PROPERTY_COMPRESSION_TYPE))
                  {
                    // Only needed check here since AS4 does not support another
                    // CompressionType
                    // http://wiki.ds.unipi.gr/display/ESENS/PR+-+AS4
                    final EAS4CompressionMode eCompressionMode = EAS4CompressionMode.getFromMimeTypeStringOrNull (sPropertyValue);
                    if (eCompressionMode == null)
                    {
                      LOGGER.error ("Error processing the UserMessage, CompressionType '" +
                                    sPropertyValue +
                                    "' of attachment '" +
                                    sAttachmentID +
                                    "' is not supported.");

                      aErrorList.add (EEbmsError.EBMS_VALUE_INCONSISTENT.getAsError (aLocale));
                      return ESuccess.FAILURE;
                    }

                    aCompressionAttachmentIDs.put (sAttachmentID, eCompressionMode);
                    bCompressionTypePresent = true;
                  }
                  else
                    if (sPropertyName.equalsIgnoreCase (MessageHelperMethods.PART_PROPERTY_CHARACTER_SET))
                    {
                      if (StringHelper.hasText (sPropertyValue))
                      {
                        final Charset aCharset = CharsetHelper.getCharsetFromNameOrNull (sPropertyValue);
                        if (aCharset == null)
                        {
                          LOGGER.error ("Value '" +
                                        sPropertyValue +
                                        "' of property '" +
                                        MessageHelperMethods.PART_PROPERTY_CHARACTER_SET +
                                        "' of attachment '" +
                                        sAttachmentID +
                                        "' is not supported");
                          aErrorList.add (EEbmsError.EBMS_VALUE_INCONSISTENT.getAsError (aLocale));
                          return ESuccess.FAILURE;
                        }
                        else
                          if (aIncomingAttachment != null)
                            aIncomingAttachment.setCharset (aCharset);
                      }
                    }
                // else we don't care about the property
              }

            // if a compressiontype is present there has to be a mimetype
            // present, to specify what mimetype the attachment was before it
            // got compressed
            if (bCompressionTypePresent && !bMimeTypePresent)
            {
              LOGGER.error ("Error processing the UserMessage, MimeType for a compressed attachment ('" +
                            sAttachmentID +
                            "') is not present.");

              aErrorList.add (EEbmsError.EBMS_VALUE_INCONSISTENT.getAsError (aLocale));
              return ESuccess.FAILURE;
            }
          }
        }

        // If Usermessage/PartInfo specified attachments and MIME attachment
        // differ throw an error
        // This may also be an indicator for "external payloads"
        if (nSpecifiedAttachments != aAttachments.size ())
        {
          LOGGER.error ("Error processing the UserMessage, the amount of specified attachments does not correlate with the actual attachments in the UserMessage. Expected " +
                        aEbms3PayloadInfo.getPartInfoCount () +
                        " but having " +
                        aAttachments.size () +
                        " attachments.");

          aErrorList.add (EEbmsError.EBMS_EXTERNAL_PAYLOAD_ERROR.getAsError (aLocale));
          return ESuccess.FAILURE;
        }
      }
    }
    else
    {
      // all vars stay null

      final Ebms3SignalMessage aSignalMessage = aMessaging.getSignalMessageAtIndex (0);

      final Ebms3PullRequest aEbms3PullRequest = aSignalMessage.getPullRequest ();
      final Ebms3Receipt aEbms3Receipt = aSignalMessage.getReceipt ();
      if (aEbms3PullRequest != null)
      {
        final IMPC aMPC = aMPCMgr.getMPCOfID (aEbms3PullRequest.getMpc ());
        if (aMPC == null)
        {
          // Return value not recognized when MPC is not currently saved
          aErrorList.add (EEbmsError.EBMS_VALUE_NOT_RECOGNIZED.getAsError (aLocale));
          return ESuccess.FAILURE;
        }

        // Create SPI which returns a PMode
        for (final IAS4ServletPullRequestProcessorSPI aProcessor : AS4ServletPullRequestProcessorManager.getAllProcessors ())
        {
          aPMode = aProcessor.processAS4UserMessage (aSignalMessage);
          if (aPMode != null)
          {
            LOGGER.info ("Found P-Mode '" + aPMode.getID () + "' for signal message " + aSignalMessage);
            break;
          }
        }

        if (aPMode == null)
        {
          aErrorList.add (EEbmsError.EBMS_VALUE_NOT_RECOGNIZED.getAsError (aLocale));
          return ESuccess.FAILURE;
        }
      }
      else
        if (aEbms3Receipt != null)
        {
          if (StringHelper.hasNoText (aSignalMessage.getMessageInfo ().getRefToMessageId ()))
          {
            aErrorList.add (EEbmsError.EBMS_INVALID_RECEIPT.getAsError (aLocale));
            return ESuccess.FAILURE;
          }
        }
        else
        {
          // Error Message
          if (!aSignalMessage.getError ().isEmpty ())
          {
            for (final Ebms3Error aError : aSignalMessage.getError ())
            {
              /*
               * Ebms 3 spec 6.2.6: This OPTIONAL attribute indicates the
               * MessageId of the message in error, for which this error is
               * raised.
               */
              if (false)
                if (StringHelper.hasNoText (aError.getRefToMessageInError ()))
                {
                  aErrorList.add (EEbmsError.EBMS_VALUE_INCONSISTENT.getAsError (aLocale));
                  return ESuccess.FAILURE;
                }
            }
          }
        }
    }

    // Remember in state
    aState.setPMode (aPMode);
    aState.setOriginalSoapDocument (aSOAPDoc);
    aState.setOriginalAttachments (aAttachments);
    aState.setCompressedAttachmentIDs (aCompressionAttachmentIDs);
    aState.setMPC (aEffectiveMPC);
    aState.setInitiatorID (sInitiatorID);
    aState.setResponderID (sResponderID);

    return ESuccess.SUCCESS;
  }
}