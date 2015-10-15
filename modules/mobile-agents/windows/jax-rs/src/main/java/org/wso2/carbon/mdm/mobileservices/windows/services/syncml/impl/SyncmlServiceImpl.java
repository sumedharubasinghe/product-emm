/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.mdm.mobileservices.windows.services.syncml.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONException;
import org.w3c.dom.Document;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSSerializer;
import org.wso2.carbon.device.mgt.common.*;
import org.wso2.carbon.device.mgt.common.notification.mgt.NotificationManagementException;
import org.wso2.carbon.device.mgt.common.operation.mgt.Operation;
import org.wso2.carbon.device.mgt.common.operation.mgt.OperationManagementException;
import org.wso2.carbon.mdm.mobileservices.windows.common.PluginConstants;
import org.wso2.carbon.mdm.mobileservices.windows.common.beans.CacheEntry;
import org.wso2.carbon.mdm.mobileservices.windows.common.exceptions.WindowsDeviceEnrolmentException;
import org.wso2.carbon.mdm.mobileservices.windows.common.util.DeviceUtil;
import org.wso2.carbon.mdm.mobileservices.windows.common.util.WindowsAPIUtils;
import org.wso2.carbon.mdm.mobileservices.windows.operations.*;
import org.wso2.carbon.mdm.mobileservices.windows.operations.util.*;
import org.wso2.carbon.mdm.mobileservices.windows.services.syncml.SyncmlService;
import org.wso2.carbon.policy.mgt.common.PolicyManagementException;
import org.wso2.carbon.policy.mgt.common.monitor.PolicyComplianceException;

import javax.ws.rs.core.Response;
import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import static org.wso2.carbon.mdm.mobileservices.windows.common.util.WindowsAPIUtils.convertToDeviceIdentifierObject;

/**
 * Implementing class of SyncmlImpl interface.
 */
public class SyncmlServiceImpl implements SyncmlService {

    private static Log log = LogFactory.getLog(SyncmlServiceImpl.class);

    /**
     * This method is used to generate and return Device object from the received information at
     * the Syncml step.
     *
     * @param deviceID     - Unique device ID received from the Device
     * @param osVersion    - Device OS version
     * @param imsi         - Device IMSI
     * @param imei         - Device IMEI
     * @param manufacturer - Device Manufacturer name
     * @param model        - Device Model
     * @return - Generated device object
     */
    private Device generateDevice(String type, String deviceID, String osVersion, String imsi,
                                  String imei, String manufacturer, String model, String user) {

        Device generatedDevice = new Device();

        Device.Property OSVersionProperty = new Device.Property();
        OSVersionProperty.setName(PluginConstants.SyncML.OS_VERSION);
        OSVersionProperty.setValue(osVersion);

        Device.Property IMSEIProperty = new Device.Property();
        IMSEIProperty.setName(PluginConstants.SyncML.IMSI);
        IMSEIProperty.setValue(imsi);

        Device.Property IMEIProperty = new Device.Property();
        IMEIProperty.setName(PluginConstants.SyncML.IMEI);
        IMEIProperty.setValue(imei);

        Device.Property DevManProperty = new Device.Property();
        DevManProperty.setName(PluginConstants.SyncML.VENDOR);
        DevManProperty.setValue(manufacturer);

        Device.Property DevModProperty = new Device.Property();
        DevModProperty.setName(PluginConstants.SyncML.MODEL);
        DevModProperty.setValue(model);

        List<Device.Property> propertyList = new ArrayList<>();
        propertyList.add(OSVersionProperty);
        propertyList.add(IMSEIProperty);
        propertyList.add(IMEIProperty);
        propertyList.add(DevManProperty);
        propertyList.add(DevModProperty);

        EnrolmentInfo enrolmentInfo = new EnrolmentInfo();
        enrolmentInfo.setOwner(user);
        enrolmentInfo.setOwnership(EnrolmentInfo.OwnerShip.BYOD);
        enrolmentInfo.setStatus(EnrolmentInfo.Status.ACTIVE);

        generatedDevice.setEnrolmentInfo(enrolmentInfo);
        generatedDevice.setDeviceIdentifier(deviceID);
        generatedDevice.setProperties(propertyList);
        generatedDevice.setType(type);

        return generatedDevice;
    }

    /**
     * Method for calling SyncML engine for producing the Syncml response. For the first SyncML message comes from
     * the device, this method produces a response to retrieve device information for enrolling the device.
     *
     * @param request - SyncML request
     * @return - SyncML response
     * @throws WindowsOperationException
     * @throws WindowsDeviceEnrolmentException
     */
    @Override
    public Response getResponse(Document request)
            throws WindowsDeviceEnrolmentException, WindowsOperationException, OperationManagementException,
            DeviceManagementException, FeatureManagementException, PolicyComplianceException, JSONException,
            PolicyManagementException, NotificationManagementException, NoSuchAlgorithmException, UnsupportedEncodingException {

        String val = SyncmlServiceImpl.getStringFromDoc(request);
        int msgID;
        int sessionId;
        String user;
        String token;
        String response;
        SyncmlDocument syncmlDocument;
        List<Operation> deviceInfoOperations;
        List<? extends Operation> pendingOperations;
        OperationUtils operationUtils = new OperationUtils();
        DeviceInfo deviceInfo = new DeviceInfo();

        if (SyncmlParser.parseSyncmlPayload(request) != null) {
            syncmlDocument = SyncmlParser.parseSyncmlPayload(request);

            SyncmlHeader syncmlHeader = syncmlDocument.getHeader();

            sessionId = syncmlHeader.getSessionId();
            user = syncmlHeader.getSource().getLocName();
            DeviceIdentifier deviceIdentifier = convertToDeviceIdentifierObject(syncmlHeader.getSource()
                    .getLocURI());
            msgID = syncmlHeader.getMsgID();
            if (PluginConstants.SyncML.SYNCML_FIRST_MESSAGE_ID == msgID &&
                    PluginConstants.SyncML.SYNCML_FIRST_SESSION_ID == sessionId) {
                token = syncmlHeader.getCredential().getData();
                CacheEntry cacheToken = (CacheEntry) DeviceUtil.getCacheEntry(token);

                if (cacheToken.getUsername().equals(user)) {

                    if (enrollDevice(request)) {
                        deviceInfoOperations = deviceInfo.getDeviceInfo();
                        try {
                            response = generateReply(syncmlDocument, deviceInfoOperations);
                            return Response.status(Response.Status.OK).entity(response).build();
                        } catch (JSONException e) {
                            throw new JSONException("Error occurred in while parsing json object.");
                        } catch (PolicyManagementException e) {
                            throw new PolicyManagementException("Error occurred in while getting effective" +
                                    " policy.", e);
                        } catch (org.wso2.carbon.policy.mgt.common.FeatureManagementException e) {
                            throw new FeatureManagementException("Error occurred in while getting effective " +
                                    "feature", e);
                        } catch (NoSuchAlgorithmException e) {
                            String msg = "Error occurred in while generating hash value.";
                            log.error(msg);
                            throw new NoSuchAlgorithmException(msg, e);
                        } catch (UnsupportedEncodingException e) {
                            String msg = "Error occurred in while encoding hash value.";
                            log.error(msg);
                            throw new UnsupportedEncodingException(msg);
                        }

                    } else {
                        String msg = "Error occurred in device enrollment.";
                        log.error(msg);
                        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(msg).build();
                    }
                } else {
                    String msg = "Authentication failure due to incorrect credentials.";
                    log.error(msg);
                    return Response.status(Response.Status.UNAUTHORIZED).entity(msg).build();
                }
            } else if (PluginConstants.SyncML.SYNCML_SECOND_MESSAGE_ID == msgID &&
                    PluginConstants.SyncML.SYNCML_FIRST_SESSION_ID == sessionId) {

                if (enrollDevice(request)) {
                    try {
                        return Response.ok().entity(generateReply(syncmlDocument, null)).build();
                    } catch (JSONException e) {
                        throw new JSONException("Error occurred in while parsing json object.");
                    } catch (PolicyManagementException e) {
                        throw new PolicyManagementException("Error occurred in while getting effective policy.", e);
                    } catch (org.wso2.carbon.policy.mgt.common.FeatureManagementException e) {
                        throw new FeatureManagementException("Error occurred in while getting effective feature", e);
                    } catch (NoSuchAlgorithmException e) {
                        String msg = "Error occurred in while generating hash value.";
                        log.error(msg);
                        throw new NoSuchAlgorithmException(msg, e);
                    } catch (UnsupportedEncodingException e) {
                        String msg = "Error occurred in while encoding hash value.";
                        log.error(msg);
                        throw new UnsupportedEncodingException(msg);
                    }

                } else {
                    String msg = "Error occurred in modify enrollment.";
                    log.error(msg);
                    return Response.status(Response.Status.NOT_MODIFIED).entity(msg).build();
                }
            } else if (sessionId >= PluginConstants.SyncML.SYNCML_SECOND_SESSION_ID) {
                if ((syncmlDocument.getBody().getAlert() != null)) {
                    if (!syncmlDocument.getBody().getAlert().getData().equals(Constants.DISENROLL_ALERT_DATA)) {
                        try {
                            pendingOperations = operationUtils.getPendingOperations(syncmlDocument);
                            String gen = generateReply(syncmlDocument, pendingOperations);
                            //return Response.ok().entity(generateReply(syncmlDocument, (List<Operation>)
                            //	pendingOperations)).build();
                            return Response.ok().entity(gen).build();

                        } catch (OperationManagementException e) {
                            String msg = "Cannot access operation management service.";
                            log.error(msg);
                            throw new OperationManagementException(msg, e);
                        } catch (DeviceManagementException e) {
                            String msg = "Cannot access Device management service.";
                            log.error(msg);
                            throw new DeviceManagementException(msg, e);
                        } catch (FeatureManagementException e) {
                            String msg = "Error occurred in getting effective features. ";
                            log.error(msg);
                            throw new FeatureManagementException(msg, e);
                        } catch (PolicyComplianceException e) {
                            String msg = "Error occurred in setting policy compliance.";
                            log.error(msg);
                            throw new PolicyComplianceException(msg, e);
                        } catch (JSONException e) {
                            throw new JSONException("Error occurred in while parsing json object.");
                        } catch (PolicyManagementException e) {
                            throw new PolicyManagementException("Error occurred in while getting effective" +
                                    " policy.", e);
                        } catch (org.wso2.carbon.policy.mgt.common.FeatureManagementException e) {
                            throw new FeatureManagementException("Error occurred in while getting effective " +
                                    "feature", e);
                        } catch (NotificationManagementException e) {
                            throw new NotificationManagementException("Error occurred in while getting notification " +
                                    "service ", e);
                        } catch (NoSuchAlgorithmException e) {
                            String msg = "Error occurred in while generating hash value.";
                            log.error(msg);
                            throw new NoSuchAlgorithmException(msg, e);
                        } catch (UnsupportedEncodingException e) {
                            String msg = "Error occurred in while encoding hash value.";
                            log.error(msg);
                            throw new UnsupportedEncodingException(msg);
                        }
                    } else {
                        try {
                            if (WindowsAPIUtils.getDeviceManagementService().getDevice(deviceIdentifier) != null) {
                                WindowsAPIUtils.getDeviceManagementService().disenrollDevice(deviceIdentifier);
                                return Response.ok().entity(generateReply(syncmlDocument, null)).build();
                            } else {
                                String msg = "Enrolled device can not be found in the server.";
                                log.error(msg);
                                return Response.status(Response.Status.NOT_FOUND).entity(msg).build();
                            }
                        } catch (DeviceManagementException e) {
                            String msg = "Failure occurred in dis-enrollment flow.";
                            log.error(msg);
                            throw new WindowsOperationException(msg, e);
                        } catch (JSONException e) {
                            throw new JSONException("Error occurred in while parsing json object.");
                        } catch (PolicyManagementException e) {
                            throw new PolicyManagementException("Error occurred in while getting" +
                                    " effective policy.", e);
                        } catch (org.wso2.carbon.policy.mgt.common.FeatureManagementException e) {
                            throw new FeatureManagementException("Error occurred in while getting " +
                                    "effective feature", e);
                        } catch (NoSuchAlgorithmException e) {
                            String msg = "Error occurred in while generating hash value.";
                            log.error(msg);
                            throw new NoSuchAlgorithmException(msg, e);
                        } catch (UnsupportedEncodingException e) {
                            String msg = "Error occurred in while encoding hash value.";
                            log.error(msg);
                            throw new UnsupportedEncodingException(msg);
                        }
                    }
                } else {
                    try {
                        pendingOperations = operationUtils.getPendingOperations(syncmlDocument);
                        String replygen = generateReply(syncmlDocument, pendingOperations);
                        //return Response.ok().entity(generateReply(syncmlDocument, (List<Operation>)pendingOperations))
                        //.build();
                        return Response.ok().entity(replygen).build();

                    } catch (OperationManagementException e) {
                        String msg = "Cannot access operation management service.";
                        log.error(msg);
                        throw new WindowsOperationException(msg, e);
                    } catch (DeviceManagementException e) {
                        String msg = "Cannot access Device management service.";
                        log.error(msg);
                        throw new WindowsOperationException(msg, e);
                    } catch (FeatureManagementException e) {
                        String msg = "Error occurred in getting effective features. ";
                        log.error(msg);
                        throw new FeatureManagementException(msg, e);
                    } catch (PolicyComplianceException e) {
                        String msg = "Error occurred in setting policy compliance.";
                        log.error(msg);
                        throw new PolicyComplianceException(msg, e);
                    } catch (JSONException e) {
                        String msg = "Error occurred in while parsing json object.";
                        log.error(msg);
                        throw new JSONException(msg);
                    } catch (PolicyManagementException e) {
                        String msg = "Error occurred in while getting effective policy.";
                        log.error(msg);
                        throw new PolicyManagementException(msg, e);
                    } catch (org.wso2.carbon.policy.mgt.common.FeatureManagementException e) {
                        String msg = "Error occurred in while getting effective feature";
                        log.error(msg);
                        throw new FeatureManagementException(msg, e);
                    } catch (NotificationManagementException e) {
                        String msg = "Error occurred in while getting notification service";
                        log.error(msg);
                        throw new NotificationManagementException(msg, e);
                    } catch (NoSuchAlgorithmException e) {
                        String msg = "Error occurred in while generating hash value.";
                        log.error(msg);
                        throw new NoSuchAlgorithmException(msg, e);
                    } catch (UnsupportedEncodingException e) {
                        String msg = "Error occurred in while encoding hash value.";
                        log.error(msg);
                        throw new UnsupportedEncodingException(msg);
                    }
                }
            } else {
                String msg = "Failure occurred in Device request message.";
                log.error(msg);
                return Response.status(Response.Status.BAD_REQUEST).entity(msg).build();
            }
        }
        return null;
    }

    /**
     * Enroll phone device
     *
     * @param request Device syncml request for the server side.
     * @return enroll state
     * @throws WindowsDeviceEnrolmentException
     * @throws WindowsOperationException
     */
    private boolean enrollDevice(Document request) throws WindowsDeviceEnrolmentException, WindowsOperationException {

        String osVersion;
        String imsi = null;
        String imei = null;
        String devID;
        String devMan;
        String devMod;
        String devLang;
        String vender;
        String macAddress;
        String resolution;
        String modVersion;
        String longitude;
        String latitude;
        boolean status = false;
        String user;
        String deviceName;
        int msgID;
        SyncmlDocument syncmlDocument;

        try {
            syncmlDocument = SyncmlParser.parseSyncmlPayload(request);
            msgID = syncmlDocument.getHeader().getMsgID();
            if (msgID == PluginConstants.SyncML.SYNCML_FIRST_MESSAGE_ID) {
                Replace replace = syncmlDocument.getBody().getReplace();
                List<Item> itemList = replace.getItems();
                devID = itemList.get(PluginConstants.SyncML.DEVICE_ID_POSITION).getData();
                devMan = itemList.get(PluginConstants.SyncML.DEVICE_MAN_POSITION).getData();
                devMod = itemList.get(PluginConstants.SyncML.DEVICE_MODEL_POSITION).getData();
                modVersion = itemList.get(PluginConstants.SyncML.DEVICE_MOD_VER_POSITION).getData();
                devLang = itemList.get(PluginConstants.SyncML.DEVICE_LANG_POSITION).getData();
                user = syncmlDocument.getHeader().getSource().getLocName();

                if (log.isDebugEnabled()) {
                    log.debug(
                            "OS Version:" + modVersion + ", DevID: " + devID + ", DevMan: " + devMan +
                                    ", DevMod: " + devMod + ", DevLang: " + devLang);
                }
                Device generateDevice = generateDevice(DeviceManagementConstants.MobileDeviceTypes.
                        MOBILE_DEVICE_TYPE_WINDOWS, devID, modVersion, imsi, imei, devMan, devMod, user);
                status = WindowsAPIUtils.getDeviceManagementService().enrollDevice(generateDevice);
                return status;

            } else if (msgID == PluginConstants.SyncML.SYNCML_SECOND_MESSAGE_ID) {
                List<Item> itemList = syncmlDocument.getBody().getResults().getItem();
                osVersion = itemList.get(PluginConstants.SyncML.OSVERSION_POSITION).getData();
                imsi = itemList.get(PluginConstants.SyncML.IMSI_POSITION).getData();
                imei = itemList.get(PluginConstants.SyncML.IMEI_POSITION).getData();
                vender = itemList.get(PluginConstants.SyncML.VENDER_POSITION).getData();
                macAddress = itemList.get(PluginConstants.SyncML.MACADDRESS_POSITION).getData();
                resolution = itemList.get(PluginConstants.SyncML.RESOLUTION_POSITION).getData();
                deviceName = itemList.get(PluginConstants.SyncML.DEVICE_NAME_POSITION).getData();
                DeviceIdentifier deviceIdentifier = convertToDeviceIdentifierObject(syncmlDocument.getHeader().getSource()
                        .getLocURI());
                Device existingDevice = WindowsAPIUtils.getDeviceManagementService().getDevice(deviceIdentifier);

                if (!existingDevice.getProperties().isEmpty()) {
                    List<Device.Property> existingProperties = new ArrayList<>();

                    Device.Property imeiProperty = new Device.Property();
                    imeiProperty.setName(PluginConstants.SyncML.IMEI);
                    imeiProperty.setValue(imei);
                    existingProperties.add(imeiProperty);

                    Device.Property osVersionProperty = new Device.Property();
                    osVersionProperty.setName(PluginConstants.SyncML.OS_VERSION);
                    osVersionProperty.setValue(osVersion);
                    existingProperties.add(osVersionProperty);

                    Device.Property imsiProperty = new Device.Property();
                    imsiProperty.setName(PluginConstants.SyncML.IMSI);
                    imsiProperty.setValue(imsi);
                    existingProperties.add(imsiProperty);

                    Device.Property venderProperty = new Device.Property();
                    venderProperty.setName(PluginConstants.SyncML.VENDOR);
                    venderProperty.setValue(vender);
                    existingProperties.add(venderProperty);

                    Device.Property macAddressProperty = new Device.Property();
                    macAddressProperty.setName(PluginConstants.SyncML.MAC_ADDRESS);
                    macAddressProperty.setValue(macAddress);
                    existingProperties.add(macAddressProperty);

                    Device.Property resolutionProperty = new Device.Property();
                    resolutionProperty.setName(PluginConstants.SyncML.DEVICE_INFO);
                    resolutionProperty.setValue(resolution);
                    existingProperties.add(resolutionProperty);

                    Device.Property deviceNameProperty = new Device.Property();
                    deviceNameProperty.setName(PluginConstants.SyncML.DEVICE_NAME);
                    deviceNameProperty.setValue(deviceName);
                    existingProperties.add(deviceNameProperty);

                    existingDevice.setProperties(existingProperties);
                    existingDevice.setDeviceIdentifier(syncmlDocument.getHeader().getSource().getLocURI());
                    existingDevice.setType(DeviceManagementConstants.MobileDeviceTypes.MOBILE_DEVICE_TYPE_WINDOWS);
                    status = WindowsAPIUtils.getDeviceManagementService().modifyEnrollment(existingDevice);
                    return status;
                }
            }
        } catch (DeviceManagementException e) {
            String msg = "Failure occurred in enrolling device.";
            log.debug(msg, e);
            throw new WindowsDeviceEnrolmentException(msg, e);
        } catch (WindowsOperationException e) {
            String msg = "Failure occurred in parsing Syncml document.";
            log.error(msg, e);
            throw new WindowsOperationException(msg, e);
        }
        return status;
    }

    // Only for testing
    public static String getStringFromDoc(org.w3c.dom.Document doc) {
        DOMImplementationLS domImplementation = (DOMImplementationLS) doc.getImplementation();
        LSSerializer lsSerializer = domImplementation.createLSSerializer();
        return lsSerializer.writeToString(doc);
    }

    /**
     * Generate Device payloads.
     *
     * @param syncmlDocument parsed suncml payload from the syncml engine.
     * @param operations     operations for generate payload.
     * @return String type syncml payload.
     * @throws WindowsOperationException
     * @throws JSONException
     * @throws PolicyManagementException
     * @throws org.wso2.carbon.policy.mgt.common.FeatureManagementException
     */
    public String generateReply(SyncmlDocument syncmlDocument, List<? extends Operation> operations)
            throws WindowsOperationException, JSONException, PolicyManagementException,
            org.wso2.carbon.policy.mgt.common.FeatureManagementException, UnsupportedEncodingException,
            NoSuchAlgorithmException {
        OperationReply operationReply;
        SyncmlGenerator generator;
        SyncmlDocument syncmlResponse;
        if (operations == null) {
            operationReply = new OperationReply(syncmlDocument);
        } else {
            operationReply = new OperationReply(syncmlDocument, operations);
        }
        syncmlResponse = operationReply.generateReply();
        generator = new SyncmlGenerator();
        return generator.generatePayload(syncmlResponse);
    }
}
