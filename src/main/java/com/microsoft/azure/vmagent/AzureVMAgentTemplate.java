/*
 Copyright 2016 Microsoft, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.microsoft.azure.vmagent;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.storage.SkuName;
import com.microsoft.azure.management.storage.StorageAccount;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.azure.util.AzureCredentials.ServicePrincipal;
import com.microsoft.azure.vmagent.exceptions.AzureCloudException;
import com.microsoft.azure.vmagent.util.AzureUtil;
import com.microsoft.azure.vmagent.util.Constants;
import com.microsoft.azure.vmagent.util.FailureStage;
import com.microsoft.azure.vmagent.util.TokenCache;
import hudson.Extension;
import hudson.RelativePath;
import hudson.model.*;
import hudson.model.labels.LabelAtom;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import javax.xml.bind.DatatypeConverter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class defines the configuration of Azure instance templates.
 *
 * @author Suresh Nallamilli
 */
public class AzureVMAgentTemplate implements Describable<AzureVMAgentTemplate> {

    public enum ImageReferenceType {
        UNKNOWN,
        CUSTOM,
        REFERENCE,
    }

    public static class ImageReferenceTypeClass {
        private String image;
        private String imagePublisher;
        private String imageOffer;
        private String imageSku;
        private String imageVersion;

        @DataBoundConstructor
        public ImageReferenceTypeClass(final String image, final String imagePublisher, final String imageOffer, final String imageSku, final String imageVersion) {
            this.image = image;
            this.imagePublisher = imagePublisher;
            this.imageOffer = imageOffer;
            this.imageSku = imageSku;
            this.imageVersion = imageVersion;
        }

        public String getImage() {
            return image;
        }

        public String getImagePublisher() {
            return imagePublisher;
        }

        public String getImageOffer() {
            return imageOffer;
        }

        public String getImageSku() {
            return imageSku;
        }

        public String getImageVersion() {
            return imageVersion;
        }
    }

    private static final Logger LOGGER = Logger.getLogger(AzureVMAgentTemplate.class.getName());

    private static final int GEN_STORAGE_ACCOUNT_UID_LENGTH = 22;

    // General Configuration
    private final String templateName;

    private final String templateDesc;

    private final String labels;

    private final String location;

    private final String virtualMachineSize;

    private String storageAccountNameReferenceType;

    private transient String storageAccountName;

    private String newStorageAccountName;

    private String existingStorageAccountName;

    private String storageAccountType;

    private final int noOfParallelJobs;

    private Node.Mode usageMode;

    private final boolean shutdownOnIdle;

    // Image Configuration
    private String imageTopLevelType;

    private final String imageReferenceType;

    private String builtInImage;

    private final String image;

    private final String osType;

    private final String imagePublisher;

    private final String imageOffer;

    private final String imageSku;

    private final String imageVersion;

    private final String agentLaunchMethod;

    private final String initScript;

    private final String credentialsId;

    private final String agentWorkspace;

    private final int retentionTimeInMin;

    private String virtualNetworkName;

    private String virtualNetworkResourceGroupName;

    private String subnetName;

    private boolean usePrivateIP;

    private final String nsgName;

    private final String jvmOptions;

    // Indicates whether the template is disabled.
    // If disabled, will not attempt to verify or use
    private final boolean templateDisabled;

    private String templateStatusDetails;

    private transient AzureVMCloud azureCloud;

    private transient Set<LabelAtom> labelDataSet;

    private boolean templateVerified;

    private boolean executeInitScriptAsRoot;

    private boolean doNotUseMachineIfInitFails;

    @DataBoundConstructor
    public AzureVMAgentTemplate(
            final String templateName,
            final String templateDesc,
            final String labels,
            final String location,
            final String virtualMachineSize,
            final String storageAccountNameReferenceType,
            final String storageAccountType,
            final String newStorageAccountName,
            final String existingStorageAccountName,
            final String noOfParallelJobs,
            final String usageMode,
            final String builtInImage,
            final String osType,
            final String  imageTopLevelType,
            final boolean imageReference,
            final ImageReferenceTypeClass imageReferenceTypeClass,
            final String agentLaunchMethod,
            final String initScript,
            final String credentialsId,
            final String virtualNetworkName,
            final String virtualNetworkResourceGroupName,
            final String subnetName,
            final boolean usePrivateIP,
            final String nsgName,
            final String agentWorkspace,
            final String jvmOptions,
            final String retentionTimeInMin,
            final boolean shutdownOnIdle,
            final boolean templateDisabled,
            final String templateStatusDetails,
            final boolean executeInitScriptAsRoot,
            final boolean doNotUseMachineIfInitFails) {
        this.templateName = templateName;
        this.templateDesc = templateDesc;
        this.labels = labels;
        this.location = location;
        this.virtualMachineSize = virtualMachineSize;
        this.storageAccountType = storageAccountType;
        this.storageAccountName = getStorageAccountName(storageAccountNameReferenceType, newStorageAccountName, existingStorageAccountName);
        this.newStorageAccountName = newStorageAccountName;
        this.existingStorageAccountName = existingStorageAccountName;
        this.storageAccountNameReferenceType = storageAccountNameReferenceType;

        if (StringUtils.isBlank(noOfParallelJobs) || !noOfParallelJobs.matches(Constants.REG_EX_DIGIT)
                || noOfParallelJobs.
                trim().equals("0")) {
            this.noOfParallelJobs = 1;
        } else {
            this.noOfParallelJobs = Integer.parseInt(noOfParallelJobs);
        }
        setUsageMode(usageMode);
        this.imageTopLevelType = imageTopLevelType;
        this.imageReferenceType = getImageReferenceType(imageReferenceTypeClass);
        this.builtInImage = builtInImage;
        this.image = imageReferenceTypeClass.getImage();
        this.osType = osType;
        this.imagePublisher = imageReferenceTypeClass.getImagePublisher();
        this.imageOffer = imageReferenceTypeClass.getImageOffer();
        this.imageSku = imageReferenceTypeClass.getImageSku();
        this.imageVersion = imageReferenceTypeClass.getImageVersion();
        this.shutdownOnIdle = shutdownOnIdle;
        this.initScript = initScript;
        this.agentLaunchMethod = agentLaunchMethod;
        this.credentialsId = credentialsId;
        this.virtualNetworkName = virtualNetworkName;
        this.virtualNetworkResourceGroupName = virtualNetworkResourceGroupName;
        this.subnetName = subnetName;
        this.usePrivateIP = usePrivateIP;
        this.nsgName = nsgName;
        this.agentWorkspace = agentWorkspace;
        this.jvmOptions = jvmOptions;
        this.executeInitScriptAsRoot = executeInitScriptAsRoot;
        this.doNotUseMachineIfInitFails = doNotUseMachineIfInitFails;
        if (StringUtils.isBlank(retentionTimeInMin) || !retentionTimeInMin.matches(Constants.REG_EX_DIGIT)) {
            this.retentionTimeInMin = Constants.DEFAULT_IDLE_TIME;
        } else {
            this.retentionTimeInMin = Integer.parseInt(retentionTimeInMin);
        }
        this.templateDisabled = templateDisabled;
        this.templateStatusDetails = "";

        // Reset the template verification status.
        this.templateVerified = false;

        // Forms data which is not persisted
        labelDataSet = Label.parse(labels);
    }

    public static Map<String, Object> getTemplateProperties(AzureVMAgentTemplate template) {
        Map<String, Object> properties = new HashMap<>();
        String builtInImage = template.getBuiltInImage();
        Boolean isBasic = template.isTopLevelType("basic");
        properties.put("imagePublisher", isBasic ? AzureVMManagementServiceDelegate.DEFAULT_IMAGE_PROPERTIES.get(builtInImage).get(Constants.DEFAULT_IMAGE_PUBLISHER) : template.getImagePublisher());
        properties.put("imageOffer", isBasic ? AzureVMManagementServiceDelegate.DEFAULT_IMAGE_PROPERTIES.get(builtInImage).get(Constants.DEFAULT_IMAGE_OFFER) : template.getImageOffer());
        properties.put("imageSku", isBasic ? AzureVMManagementServiceDelegate.DEFAULT_IMAGE_PROPERTIES.get(builtInImage).get(Constants.DEFAULT_IMAGE_SKU) : template.getImageSku());
        properties.put("imageVersion", isBasic ? AzureVMManagementServiceDelegate.DEFAULT_IMAGE_PROPERTIES.get(builtInImage).get(Constants.DEFAULT_IMAGE_VERSION) : template.getImageVersion());
        properties.put("osType", isBasic ? AzureVMManagementServiceDelegate.DEFAULT_IMAGE_PROPERTIES.get(builtInImage).get(Constants.DEFAULT_OS_TYPE) : template.getOsType());
        properties.put("agentLaunchMethod", isBasic ? AzureVMManagementServiceDelegate.DEFAULT_IMAGE_PROPERTIES.get(builtInImage).get(Constants.DEFAULT_LAUNCH_METHOD) : template.getAgentLaunchMethod());
        properties.put("initScript", isBasic ? AzureVMManagementServiceDelegate.DEFAULT_INIT_SCRIPT.get(builtInImage) : template.getInitScript());
        properties.put("virtualNetworkName", isBasic ? "" : template.getVirtualNetworkName());
        properties.put("virtualNetworkResourceGroupName", isBasic ? "" : template.getVirtualNetworkResourceGroupName());
        properties.put("subnetName", isBasic ? "" : template.getSubnetName());
        properties.put("usePrivateIP", isBasic ? false : template.getUsePrivateIP());
        properties.put("nsgName", isBasic ? "" : template.getNsgName());
        properties.put("jvmOptions", isBasic ? "" : template.getJvmOptions());
        properties.put("noOfParallelJobs", isBasic ? 1 : template.getNoOfParallelJobs());
        properties.put("templateDisabled", isBasic ? false : template.isTemplateDisabled());
        properties.put("executeInitScriptAsRoot", isBasic ? true : template.getExecuteInitScriptAsRoot());
        properties.put("doNotUseMachineIfInitFails", isBasic ? true : template.getDoNotUseMachineIfInitFails());
        return properties;
    }


    public Boolean isType(final String type) {
        if (this.imageReferenceType == null && type.equals("reference")) {
            return true;
        }
        return type != null && type.equalsIgnoreCase(this.imageReferenceType);
    }

    public Boolean isTopLevelType(final String type) {
        if (this.imageTopLevelType == null && type.equals("basic")) {
            return true;
        }
        return type != null && type.equalsIgnoreCase(this.imageTopLevelType);
    }

    private Object readResolve() {
        labelDataSet = Label.parse(labels);

        if (StringUtils.isBlank(storageAccountType)) {
            storageAccountType = SkuName.STANDARD_LRS.toString();
        }

        if (StringUtils.isBlank(newStorageAccountName) && StringUtils.isBlank(existingStorageAccountName)
                && StringUtils.isNotBlank(storageAccountName)) {
            newStorageAccountName = storageAccountName;
            storageAccountNameReferenceType = "new";
        }
        storageAccountName = getStorageAccountName(storageAccountNameReferenceType, newStorageAccountName, existingStorageAccountName);

        if (StringUtils.isBlank(imageTopLevelType)) {
            if (StringUtils.isNotBlank(image)
                    || StringUtils.isNotBlank(imageOffer)
                    || StringUtils.isNotBlank(imageSku)
                    || StringUtils.isNotBlank(imagePublisher)) {
                imageTopLevelType = "advanced";
            } else {
                imageTopLevelType = "basic";
            }
            builtInImage = Constants.WINDOWS_SERVER_2016;
        }
        return this;
    }

    public String getLabels() {
        return labels;
    }

    public String getLocation() {
        return location;
    }

    public String getVirtualMachineSize() {
        return virtualMachineSize;
    }

    public String getStorageAccountType() {
        return StringUtils.isBlank(storageAccountType) ? SkuName.STANDARD_LRS.toString() : storageAccountType;
    }

    public String getStorageAccountName() {
        return storageAccountName;
    }

    public static String getStorageAccountName(final String type, final String newName, final String existingName) {
        //type maybe null in this version, so we can guess according to whether newName is blank or not
        if (StringUtils.isBlank(type) && StringUtils.isNotBlank(newName)
                || StringUtils.isNotBlank(type) && type.equalsIgnoreCase("new")) {
            return newName;
        }
        return existingName;
    }

    public String getStorageAccountNameReferenceType() {
        return storageAccountNameReferenceType;
    }

    public void setStorageAccountName(final String storageAccountName) {
        this.storageAccountName = storageAccountName;
    }

    public String getNewStorageAccountName() {
        return newStorageAccountName;
    }

    public String getExistingStorageAccountName() {
        return existingStorageAccountName;
    }

    public Node.Mode getUseAgentAlwaysIfAvail() {
        return (usageMode == null) ? Node.Mode.NORMAL : usageMode;
    }

    public Boolean isStorageAccountNameReferenceTypeEquals(final String type) {
        if (this.storageAccountNameReferenceType == null && type.equalsIgnoreCase("new")) {
            return true;
        }
        return type != null && type.equalsIgnoreCase(this.storageAccountNameReferenceType);
    }

    public String getUsageMode() {
        return getUseAgentAlwaysIfAvail().getDescription();
    }

    public void setUsageMode(final String mode) {
        Node.Mode val = Node.Mode.NORMAL;
        for (Node.Mode m : hudson.Functions.getNodeModes()) {
            if (mode.equalsIgnoreCase(m.getDescription())) {
                val = m;
                break;
            }
        }
        this.usageMode = val;
    }

    public boolean isShutdownOnIdle() {
        return shutdownOnIdle;
    }

    public String getImageReferenceType() {
        return imageReferenceType;
    }

    public String getImageTopLevelType() {
        return imageTopLevelType;
    }

    public String getImageReferenceType(ImageReferenceTypeClass imageReferenceTypeClass) {
        if (imageReferenceTypeClass.image != null) {
            return "custom";
        }
        return "reference";
    }

    public String getBuiltInImage() {
        return builtInImage;
    }

    public String getImage() {
        return image;
    }

    public String getOsType() {
        return osType;
    }

    public String getImagePublisher() {
        return imagePublisher;
    }

    public String getImageOffer() {
        return imageOffer;
    }

    public String getImageSku() {
        return imageSku;
    }

    public String getImageVersion() {
        return imageVersion;
    }

    public String getInitScript() {
        return initScript;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public StandardUsernamePasswordCredentials getVMCredentials() throws AzureCloudException {
        return AzureUtil.getCredentials(credentialsId);
    }

    public String getVirtualNetworkName() {
        return virtualNetworkName;
    }

    public void setVirtualNetworkName(String virtualNetworkName) {
        this.virtualNetworkName = virtualNetworkName;
    }

    public String getVirtualNetworkResourceGroupName() {
        return this.virtualNetworkResourceGroupName;
    }

    public String getSubnetName() {
        return subnetName;
    }

    public void setSubnetName(String subnetName) {
        this.subnetName = subnetName;
    }

    public boolean getUsePrivateIP() {
        return usePrivateIP;
    }

    public String getNsgName() {
        return nsgName;
    }

    public String getAgentWorkspace() {
        return agentWorkspace;
    }

    public int getRetentionTimeInMin() {
        return retentionTimeInMin;
    }

    public String getJvmOptions() {
        return jvmOptions;
    }

    public AzureVMCloud getAzureCloud() {
        return azureCloud;
    }

    public void setAzureCloud(AzureVMCloud cloud) {
        azureCloud = cloud;
        if (StringUtils.isBlank(storageAccountName)) {
            storageAccountName = AzureVMAgentTemplate.generateUniqueStorageAccountName(azureCloud.getResourceGroupName(), azureCloud.getServicePrincipal());
            newStorageAccountName = storageAccountName;
            //if storageAccountNameReferenceType equals existing, we help to choose new directly
            storageAccountNameReferenceType = "new";
        }
    }

    public String getTemplateName() {
        return templateName;
    }

    public String getTemplateDesc() {
        return templateDesc;
    }

    public int getNoOfParallelJobs() {
        return noOfParallelJobs;
    }

    public String getAgentLaunchMethod() {
        return agentLaunchMethod;
    }

    /**
     * Returns true if this template is disabled and cannot be used, false
     * otherwise.
     *
     * @return True/false
     */
    public boolean isTemplateDisabled() {
        return this.templateDisabled;
    }

    /**
     * Is the template set up and verified?
     *
     * @return True if the template is set up and verified, false otherwise.
     */
    public boolean isTemplateVerified() {
        return templateVerified;
    }

    /**
     * Set the template verification status.
     *
     * @param isValid True for verified + valid, false otherwise.
     */
    public void setTemplateVerified(boolean isValid) {
        templateVerified = isValid;
    }

    public String getTemplateStatusDetails() {
        return templateStatusDetails;
    }

    public void setTemplateStatusDetails(String templateStatusDetails) {
        this.templateStatusDetails = templateStatusDetails;
    }

    public String getResourceGroupName() {
        // Allow overriding?
        return getAzureCloud().getResourceGroupName();
    }

    public boolean getExecuteInitScriptAsRoot() {
        return executeInitScriptAsRoot;
    }

    public void setExecuteInitScriptAsRoot(boolean executeAsRoot) {
        executeInitScriptAsRoot = executeAsRoot;
    }

    public boolean getDoNotUseMachineIfInitFails() {
        return doNotUseMachineIfInitFails;
    }

    public void setDoNotUseMachineIfInitFails(boolean doNotUseMachineIfInitFails) {
        this.doNotUseMachineIfInitFails = doNotUseMachineIfInitFails;
    }

    @SuppressWarnings("unchecked")
    public Descriptor<AzureVMAgentTemplate> getDescriptor() {
        return Jenkins.getInstance().getDescriptor(getClass());
    }

    public Set<LabelAtom> getLabelDataSet() {
        return labelDataSet;
    }

    /**
     * Provision new agents using this template.
     *
     * @param listener
     * @param numberOfAgents Number of agents to provision
     * @return New deployment info if the provisioning was successful.
     * @throws Exception May throw if provisioning was not successful.
     */
    public AzureVMDeploymentInfo provisionAgents(final TaskListener listener, int numberOfAgents) throws Exception {
        return AzureVMManagementServiceDelegate.createDeployment(this, numberOfAgents);
    }

    /**
     * If provisioning failed, handle the status and queue the template for
     * verification.
     *
     * @param message     Failure message
     * @param failureStep Stage that failure occurred
     */
    public void handleTemplateProvisioningFailure(final String message, final FailureStage failureStep) {
        // The template is bad.  It should have already been verified, but
        // perhaps something changed (VHD gone, etc.).  Queue for verification.
        setTemplateVerified(false);
        AzureVMCloudVerificationTask.registerTemplate(this);
        // Set the details so that it's easier to see what's going on from the configuration UI.
        setTemplateStatusDetails(message);
    }

    /**
     * Verify that this template is correct and can be allocated.
     *
     * @return Empty list if this template is valid, list of errors otherwise
     * @throws Exception
     */
    public List<String> verifyTemplate() throws Exception {
        return AzureVMManagementServiceDelegate.verifyTemplate(azureCloud.getServicePrincipal(),
                templateName,
                labels,
                location,
                virtualMachineSize,
                storageAccountName,
                storageAccountType,
                noOfParallelJobs + "",
                imageTopLevelType,
                (imageReferenceType == null) ? ImageReferenceType.UNKNOWN
                        : ((imageReferenceType.equals("custom") ? ImageReferenceType.CUSTOM
                        : ImageReferenceType.REFERENCE)),
                builtInImage,
                image,
                osType,
                imagePublisher,
                imageOffer,
                imageSku,
                imageVersion,
                agentLaunchMethod,
                initScript,
                credentialsId,
                virtualNetworkName,
                virtualNetworkResourceGroupName,
                subnetName,
                retentionTimeInMin + "",
                jvmOptions,
                getResourceGroupName(),
                true,
                usePrivateIP,
                nsgName);
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<AzureVMAgentTemplate> {

        @Override
        public String getDisplayName() {
            return "";
        }

        public ListBoxModel doFillVirtualMachineSizeItems(
                @RelativePath("..") @QueryParameter final String azureCredentialsId,
                @QueryParameter final String location)
                throws IOException, ServletException {

            AzureCredentials.ServicePrincipal servicePrincipal = AzureCredentials.getServicePrincipal(azureCredentialsId);
            ListBoxModel model = new ListBoxModel();
            List<String> vmSizes = AzureVMManagementServiceDelegate.getVMSizes(servicePrincipal, location);

            if (vmSizes != null) {
                for (String vmSize : vmSizes) {
                    model.add(vmSize);
                }
            }
            return model;
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item owner) {
            // when configuring the job, you only want those credentials that are available to ACL.SYSTEM selectable
            // as we cannot select from a user's credentials unless they are the only user submitting the build
            // (which we cannot assume) thus ACL.SYSTEM is correct here.
            return new StandardListBoxModel().withAll(CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class, owner, ACL.SYSTEM, Collections.<DomainRequirement>emptyList()));
        }

        public ListBoxModel doFillOsTypeItems() throws IOException, ServletException {
            ListBoxModel model = new ListBoxModel();
            model.add(Constants.OS_TYPE_LINUX);
            model.add(Constants.OS_TYPE_WINDOWS);
            return model;
        }

        public ListBoxModel doFillLocationItems(
                @RelativePath("..") @QueryParameter final String azureCredentialsId)
                throws IOException, ServletException {
            AzureCredentials.ServicePrincipal servicePrincipal = AzureCredentials.getServicePrincipal(azureCredentialsId);

            ListBoxModel model = new ListBoxModel();

            Set<String> locations = AzureVMManagementServiceDelegate.getVirtualMachineLocations(servicePrincipal);

            for (String location : locations) {
                model.add(location);
            }

            return model;
        }

        public ListBoxModel doFillStorageAccountTypeItems(
                @QueryParameter final String virtualMachineSize)
                throws IOException, ServletException {

            ListBoxModel model = new ListBoxModel();

            model.add(SkuName.STANDARD_LRS.toString());

            /*As introduced in Azure Docs, VmSize among DS/GS/FS/LS supports premium storage*/
            if (virtualMachineSize.matches(".*(D|G|F[0-9]+|L[0-9]+)[Ss].*")) {
                model.add(SkuName.PREMIUM_LRS.toString());
            }
            return model;
        }


        public ListBoxModel doFillUsageModeItems() throws IOException, ServletException {
            ListBoxModel model = new ListBoxModel();
            for (Node.Mode m : hudson.Functions.getNodeModes()) {
                model.add(m.getDescription());
            }
            return model;
        }

        public ListBoxModel doFillAgentLaunchMethodItems() {
            ListBoxModel model = new ListBoxModel();
            model.add(Constants.LAUNCH_METHOD_SSH);
            model.add(Constants.LAUNCH_METHOD_JNLP);

            return model;
        }

        public ListBoxModel doFillExistingStorageAccountNameItems(
                @RelativePath("..") @QueryParameter final String azureCredentialsId,
                @RelativePath("..") @QueryParameter final String resourceGroupReferenceType,
                @RelativePath("..") @QueryParameter final String newResourceGroupName,
                @RelativePath("..") @QueryParameter final String existingResourceGroupName,
                @QueryParameter final String storageAccountType) throws IOException, ServletException {
            ListBoxModel model = new ListBoxModel();
            if (StringUtils.isBlank(azureCredentialsId)) {
                return model;
            }

            try {
                AzureCredentials.ServicePrincipal servicePrincipal = AzureCredentials.getServicePrincipal(azureCredentialsId);
                Azure azureClient = TokenCache.getInstance(servicePrincipal).getAzureClient();

                String resourceGroupName = AzureVMCloud.getResourceGroupName(resourceGroupReferenceType, newResourceGroupName, existingResourceGroupName);
                List<StorageAccount> storageAccountList = azureClient.storageAccounts().listByGroup(resourceGroupName);
                for (StorageAccount storageAccount : storageAccountList) {
                    if (storageAccount.sku().name().toString().equalsIgnoreCase(storageAccountType)) {
                        model.add(storageAccount.name());
                    }
                }

            } catch (Exception e) {
                LOGGER.log(Level.INFO, "Cannot list storage account: {0}", e);
            } finally {
                return model;
            }
        }

        public ListBoxModel doFillBuiltInImageItems() {
            ListBoxModel model = new ListBoxModel();
            model.add(Constants.WINDOWS_SERVER_2016);
            model.add(Constants.UBUNTU_1604_LTS);
            return model;
        }

        public FormValidation doCheckInitScript(
                @QueryParameter final String value,
                @QueryParameter final String agentLaunchMethod) {
            if (StringUtils.isBlank(value)) {
                return FormValidation.warningWithMarkup(Messages.Azure_GC_InitScript_Warn_Msg());
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckStorageAccountName(@QueryParameter final String value) {
            if (StringUtils.isBlank(value)) {
                return FormValidation.ok(Messages.SA_Blank_Create_New());
            }
            return FormValidation.ok();
        }

        public FormValidation doAgentLaunchMethod(@QueryParameter final String value) {
            if (Constants.LAUNCH_METHOD_JNLP.equals(value)) {
                return FormValidation.warning(Messages.Azure_GC_LaunchMethod_Warn_Msg());
            }
            return FormValidation.ok();
        }

        /**
         * Check the template's name. Name must conform to restrictions on VM
         * naming
         *
         * @param value            Current name
         * @param templateDisabled Is the template disabled
         * @param osType           OS type
         * @return
         */
        public FormValidation doCheckTemplateName(
                @QueryParameter final String value, @QueryParameter final boolean templateDisabled,
                @QueryParameter final String osType) {
            List<FormValidation> errors = new ArrayList<>();
            // Check whether the template name is valid, and then check
            // whether it would be shortened on VM creation.
            if (!AzureUtil.isValidTemplateName(value)) {
                errors.add(FormValidation.error(Messages.Azure_GC_Template_Name_Not_Valid()));
            }

            if (templateDisabled) {
                errors.add(FormValidation.warning(Messages.Azure_GC_TemplateStatus_Warn_Msg()));
            }

            if (errors.size() > 0) {
                return FormValidation.aggregate(errors);
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckNoOfParallelJobs(@QueryParameter final String value) {
            if (StringUtils.isNotBlank(value)) {
                String result = AzureVMManagementServiceDelegate.verifyNoOfExecutors(value);

                if (result.equalsIgnoreCase(Constants.OP_SUCCESS)) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error(result);
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckRetentionTimeInMin(@QueryParameter final String value) {
            if (StringUtils.isNotBlank(value)) {
                String result = AzureVMManagementServiceDelegate.verifyRetentionTime(value);

                if (result.equalsIgnoreCase(Constants.OP_SUCCESS)) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error(result);
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckAdminPassword(@QueryParameter final String value) {
            if (StringUtils.isNotBlank(value)) {
                if (AzureUtil.isValidPassword(value)) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error(Messages.Azure_GC_Password_Err());
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckJvmOptions(@QueryParameter final String value) {
            if (StringUtils.isNotBlank(value)) {
                if (AzureUtil.isValidJvmOption(value)) {
                    return FormValidation.ok();
                } else {
                    return FormValidation.error(Messages.Azure_GC_JVM_Option_Err());
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doVerifyConfiguration(
                @RelativePath("..") @QueryParameter String azureCredentialsId,
                @RelativePath("..") @QueryParameter String resourceGroupReferenceType,
                @RelativePath("..") @QueryParameter String newResourceGroupName,
                @RelativePath("..") @QueryParameter String existingResourceGroupName,
                @RelativePath("..") @QueryParameter String maxVirtualMachinesLimit,
                @RelativePath("..") @QueryParameter String deploymentTimeout,
                @QueryParameter String templateName,
                @QueryParameter String labels,
                @QueryParameter String location,
                @QueryParameter String virtualMachineSize,
                @QueryParameter String storageAccountNameReferenceType,
                @QueryParameter String newStorageAccountName,
                @QueryParameter String existingStorageAccountName,
                @QueryParameter String storageAccountType,
                @QueryParameter String noOfParallelJobs,
                @QueryParameter String imageTopLevelType,
                @QueryParameter String builtInImage,
                @RelativePath("imageReferenceTypeClass") @QueryParameter String image,
                @QueryParameter String osType,
                @RelativePath("imageReferenceTypeClass") @QueryParameter String imagePublisher,
                @RelativePath("imageReferenceTypeClass") @QueryParameter String imageOffer,
                @RelativePath("imageReferenceTypeClass") @QueryParameter String imageSku,
                @RelativePath("imageReferenceTypeClass") @QueryParameter String imageVersion,
                @QueryParameter String agentLaunchMethod,
                @QueryParameter String initScript,
                @QueryParameter String credentialsId,
                @QueryParameter String virtualNetworkName,
                @QueryParameter String virtualNetworkResourceGroupName,
                @QueryParameter String subnetName,
                @QueryParameter boolean usePrivateIP,
                @QueryParameter String nsgName,
                @QueryParameter String retentionTimeInMin,
                @QueryParameter String jvmOptions,
                @QueryParameter String imageReferenceType) {

            /*
            imageReferenceType will not be passed to doVerifyConfiguration unless Jenkins core has https://github.com/jenkinsci/jenkins/pull/2734
            The plugin should be able to run in both modes.
            */
            ImageReferenceType referenceType = ImageReferenceType.UNKNOWN;
            if (imageReferenceType != null) {
                referenceType = imageReferenceType.equals("custom") ? ImageReferenceType.CUSTOM : ImageReferenceType.REFERENCE;
            }
            AzureCredentials.ServicePrincipal servicePrincipal = AzureCredentials.getServicePrincipal(azureCredentialsId);
            String resourceGroupName = AzureVMCloud.getResourceGroupName(resourceGroupReferenceType, newResourceGroupName, existingResourceGroupName);
            String storageAccountName = getStorageAccountName(storageAccountNameReferenceType, newStorageAccountName, existingStorageAccountName);
            if (storageAccountName.trim().isEmpty()) {
                storageAccountName = AzureVMAgentTemplate.generateUniqueStorageAccountName(resourceGroupName, servicePrincipal);
            }

            LOGGER.log(Level.INFO,
                    "Verify configuration:\n\t"
                            + "subscriptionId: {0};\n\t"
                            + "clientId: {1};\n\t"
                            + "clientSecret: {2};\n\t"
                            + "serviceManagementURL: {3};\n\t"
                            + "resourceGroupName: {4};\n\t."
                            + "templateName: {5};\n\t"
                            + "labels: {6};\n\t"
                            + "location: {7};\n\t"
                            + "virtualMachineSize: {8};\n\t"
                            + "storageAccountName: {9};\n\t"
                            + "noOfParallelJobs: {10};\n\t"
                            + "imageTopLevelType: {11};\n\t"
                            + "builtInImage: {12};\n\t"
                            + "image: {13};\n\t"
                            + "osType: {14};\n\t"
                            + "imagePublisher: {15};\n\t"
                            + "imageOffer: {16};\n\t"
                            + "imageSku: {17};\n\t"
                            + "imageVersion: {18};\n\t"
                            + "agentLaunchMethod: {19};\n\t"
                            + "initScript: {20};\n\t"
                            + "credentialsId: {21};\n\t"
                            + "virtualNetworkName: {22};\n\t"
                            + "virtualNetworkResourceGroupName: {23};\n\t"
                            + "subnetName: {24};\n\t"
                            + "privateIP: {25};\n\t"
                            + "nsgName: {26};\n\t"
                            + "retentionTimeInMin: {27};\n\t"
                            + "jvmOptions: {28};",
                    new Object[]{
                            servicePrincipal.getSubscriptionId(),
                            (StringUtils.isNotBlank(servicePrincipal.getClientId()) ? "********" : null),
                            (StringUtils.isNotBlank(servicePrincipal.getClientSecret()) ? "********" : null),
                            servicePrincipal.getServiceManagementURL(),
                            resourceGroupName,
                            templateName,
                            labels,
                            location,
                            virtualMachineSize,
                            storageAccountName,
                            noOfParallelJobs,
                            imageTopLevelType,
                            builtInImage,
                            image,
                            osType,
                            imagePublisher,
                            imageOffer,
                            imageSku,
                            imageVersion,
                            agentLaunchMethod,
                            initScript,
                            credentialsId,
                            virtualNetworkName,
                            virtualNetworkResourceGroupName,
                            subnetName,
                            usePrivateIP,
                            nsgName,
                            retentionTimeInMin,
                            jvmOptions});

            // First validate the subscription info.  If it is not correct,
            // then we can't validate the
            String result = AzureVMManagementServiceDelegate.verifyConfiguration(servicePrincipal, resourceGroupName,
                    maxVirtualMachinesLimit, deploymentTimeout);
            if (!result.equals(Constants.OP_SUCCESS)) {
                return FormValidation.error(result);
            }

            final List<String> errors = AzureVMManagementServiceDelegate.verifyTemplate(
                    servicePrincipal,
                    templateName,
                    labels,
                    location,
                    virtualMachineSize,
                    storageAccountName,
                    storageAccountType,
                    noOfParallelJobs,
                    imageTopLevelType,
                    referenceType,
                    builtInImage,
                    image,
                    osType,
                    imagePublisher,
                    imageOffer,
                    imageSku,
                    imageVersion,
                    agentLaunchMethod,
                    initScript,
                    credentialsId,
                    virtualNetworkName,
                    virtualNetworkResourceGroupName,
                    subnetName,
                    retentionTimeInMin,
                    jvmOptions,
                    resourceGroupName,
                    false,
                    usePrivateIP,
                    nsgName);

            if (errors.size() > 0) {
                StringBuilder errorString = new StringBuilder(Messages.Azure_GC_Template_Error_List()).append("\n");

                for (int i = 0; i < errors.size(); i++) {
                    errorString.append(i + 1).append(": ").append(errors.get(i)).append("\n");
                }

                return FormValidation.error(errorString.toString());

            } else {
                return FormValidation.ok(Messages.Azure_Template_Config_Success());
            }
        }

        public String getDefaultNoOfExecutors() {
            return "1";
        }
    }

    public void setVirtualMachineDetails(final AzureVMAgent agent) throws Exception {
        AzureVMManagementServiceDelegate.setVirtualMachineDetails(agent, this);
    }

    public static String generateUniqueStorageAccountName(final String resourceGroupName, final ServicePrincipal servicePrincipal) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            if (null != servicePrincipal && !StringUtils.isEmpty(servicePrincipal.getSubscriptionId())) {
                md.update(servicePrincipal.getSubscriptionId().getBytes("UTF-8"));
            }
            if (null != resourceGroupName) {
                md.update(resourceGroupName.getBytes("UTF-8"));
            }

            String uid = DatatypeConverter.printBase64Binary(md.digest());
            uid = uid.substring(0, GEN_STORAGE_ACCOUNT_UID_LENGTH);
            uid = uid.toLowerCase();
            uid = uid.replaceAll("[^a-z0-9]", "a");
            return "jn" + uid;
        } catch (UnsupportedEncodingException | NoSuchAlgorithmException e) {
            LOGGER.log(Level.WARNING, "Could not genetare UID from the resource group name. Will fallback on using the resource group name.", e);
            return "";
        }
    }
}
