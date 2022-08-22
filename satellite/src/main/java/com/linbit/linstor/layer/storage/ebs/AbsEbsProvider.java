package com.linbit.linstor.layer.storage.ebs;

import com.linbit.ImplementationError;
import com.linbit.extproc.ExtCmdFactoryStlt;
import com.linbit.linstor.InternalApiConsts;
import com.linbit.linstor.LinStorException;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.api.DecryptionHelper;
import com.linbit.linstor.api.SpaceInfo;
import com.linbit.linstor.backupshipping.BackupShippingMgr;
import com.linbit.linstor.clone.CloneService;
import com.linbit.linstor.core.CoreModule.RemoteMap;
import com.linbit.linstor.core.StltConfigAccessor;
import com.linbit.linstor.core.StltSecurityObjects;
import com.linbit.linstor.core.apicallhandler.StltExtToolsChecker;
import com.linbit.linstor.core.identifier.ResourceName;
import com.linbit.linstor.core.identifier.StorPoolName;
import com.linbit.linstor.core.identifier.VolumeNumber;
import com.linbit.linstor.core.objects.Resource;
import com.linbit.linstor.core.objects.Snapshot;
import com.linbit.linstor.core.objects.StorPool;
import com.linbit.linstor.core.objects.Volume;
import com.linbit.linstor.core.objects.remotes.EbsRemote;
import com.linbit.linstor.dbdrivers.DatabaseException;
import com.linbit.linstor.layer.DeviceLayer.NotificationListener;
import com.linbit.linstor.layer.DeviceLayerUtils;
import com.linbit.linstor.layer.storage.AbsStorageProvider;
import com.linbit.linstor.layer.storage.WipeHandler;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.propscon.InvalidKeyException;
import com.linbit.linstor.propscon.InvalidValueException;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.snapshotshipping.SnapshotShippingService;
import com.linbit.linstor.storage.StorageException;
import com.linbit.linstor.storage.data.provider.ebs.EbsData;
import com.linbit.linstor.storage.kinds.DeviceProviderKind;
import com.linbit.linstor.transaction.manager.TransactionMgr;

import javax.annotation.Nullable;
import javax.inject.Provider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeVolumesRequest;
import com.amazonaws.services.ec2.model.DescribeVolumesResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Tag;

public abstract class AbsEbsProvider<INFO> extends AbsStorageProvider<INFO, EbsData<Resource>, EbsData<Snapshot>>
{
    /** <code>"${spName}/${rscName}$[rscSuffix}_${snapName}_${vlmNr}"</code> */
    public static final String FORMAT_SNAP_TO_LVM_ID = "%s/%s%s_%s_%05d";
    /** <code>"${spName}/${rscName}$[rscSuffix}_${vlmNr}"</code> */
    public static final String FORMAT_RSC_TO_LVM_ID = "%s/%s%s_%05d";

    protected static final String EBS_VLM_STATE_AVAILABLE = "available";
    protected static final String EBS_VLM_STATE_CREATING = "creating";
    protected static final String TAG_KEY_LINSTOR_ID = "LinstorID";
    protected static final String TAG_KEY_LINSTOR_INIT_DEV = "LinstorInitDevice";

    protected static final SpaceInfo ENOUGH_SPACE_INFO = new SpaceInfo(
        ApiConsts.VAL_STOR_POOL_SPACE_ENOUGH,
        ApiConsts.VAL_STOR_POOL_SPACE_ENOUGH
    );
    protected static final int TOLERANCE_FACTOR = 3;

    private final Map<EbsRemote, AmazonEC2> amazonEc2ClientLUT;
    protected final RemoteMap remoteMap;
    private final DecryptionHelper decHelper;
    private final StltSecurityObjects stltSecObj;

    AbsEbsProvider(
        ErrorReporter errorReporterRef,
        ExtCmdFactoryStlt extCmdFactoryRef,
        AccessContext storDriverAccCtxRef,
        StltConfigAccessor stltConfigAccessorRef,
        WipeHandler wipeHandlerRef,
        Provider<NotificationListener> notificationListenerProviderRef,
        Provider<TransactionMgr> transMgrProviderRef,
        String typeDescrRef,
        DeviceProviderKind kindRef,
        SnapshotShippingService snapShipMgrRef,
        StltExtToolsChecker extToolsCheckerRef,
        CloneService cloneServiceRef,
        BackupShippingMgr backupShipMgrRef,
        RemoteMap remoteMapRef,
        DecryptionHelper decHelperRef,
        StltSecurityObjects stltSecObjRef
    )
    {
        super(
            errorReporterRef,
            extCmdFactoryRef,
            storDriverAccCtxRef,
            stltConfigAccessorRef,
            wipeHandlerRef,
            notificationListenerProviderRef,
            transMgrProviderRef,
            typeDescrRef,
            kindRef,
            snapShipMgrRef,
            extToolsCheckerRef,
            cloneServiceRef,
            backupShipMgrRef
        );
        remoteMap = remoteMapRef;
        decHelper = decHelperRef;
        stltSecObj = stltSecObjRef;

        amazonEc2ClientLUT = new HashMap<>();
    }

    protected AmazonEC2 getClient(StorPool storPoolRef) throws AccessDeniedException, StorageException
    {
        return getClient(getEbsRemote(storPoolRef));
    }

    protected AmazonEC2 getClient(EbsRemote remoteRef) throws AccessDeniedException, StorageException
    {
        AmazonEC2 client = amazonEc2ClientLUT.get(remoteRef); // to avoid double-locking problem
        if (client == null)
        {
            synchronized (amazonEc2ClientLUT)
            {
                client = amazonEc2ClientLUT.get(remoteRef); // update, just to make sure
                if (client == null)
                {
                    EndpointConfiguration endpointConfiguration = new EndpointConfiguration(
                        remoteRef.getUrl(storDriverAccCtx).toString(),
                        remoteRef.getRegion(storDriverAccCtx)
                    );
                    byte[] masterKey = stltSecObj.getCryptKey();
                    try
                    {
                        client = AmazonEC2ClientBuilder.standard()
                            .withEndpointConfiguration(endpointConfiguration)
                            .withCredentials(
                                new AWSStaticCredentialsProvider(
                                    new BasicAWSCredentials(
                                        new String(
                                            decHelper.decrypt(
                                                masterKey,
                                                remoteRef.getEncryptedAccessKey(storDriverAccCtx)
                                            )
                                        ),
                                        new String(
                                            decHelper.decrypt(
                                                masterKey,
                                                remoteRef.getEncryptedSecretKey(storDriverAccCtx)
                                            )
                                        )
                                    )
                                )
                            ).build();
                    }
                    catch (LinStorException exc)
                    {
                        String errMsg = "Failed to decrypt access / secret key.";
                        if (masterKey == null || masterKey.length == 0)
                        {
                            errMsg += " MasterKey is missing.";
                        }
                        throw new StorageException(errMsg, exc);
                    }

                    amazonEc2ClientLUT.put(remoteRef, client);
                }
            }
        }
        return client;
    }

    public void recacheAmazonClient(EbsRemote remote)
    {
        synchronized (amazonEc2ClientLUT)
        {
            amazonEc2ClientLUT.remove(remote);
        }
    }

    protected Map<String, com.amazonaws.services.ec2.model.Volume> getTargetInfoListImpl(
        List<EbsData<Resource>> vlmDataListRef,
        List<EbsData<Snapshot>> snapVlmsRef
    )
        throws AccessDeniedException, StorageException
    {
        final Map<String, com.amazonaws.services.ec2.model.Volume> ret = new HashMap<>();

        final Set<StorPool> storPools = new HashSet<>();
        {
            List<EbsData<?>> combinedList = new ArrayList<>(vlmDataListRef);
            combinedList.addAll(snapVlmsRef);
            for (EbsData<?> data : combinedList)
            {
                storPools.add(data.getStorPool());
            }
        }

        for (StorPool storPool : storPools)
        {
            EbsRemote ebsRemote = getEbsRemote(storPool);
            AmazonEC2 client = getClient(ebsRemote);
            DescribeVolumesResult volumesResult = client.describeVolumes(
                new DescribeVolumesRequest().withFilters(
                    new Filter("availability-zone", Arrays.asList(ebsRemote.getAvailabilityZone(storDriverAccCtx)))
                )
            );
            for (com.amazonaws.services.ec2.model.Volume amazonVolume : volumesResult.getVolumes())
            {
                ret.put(amazonVolume.getVolumeId(), amazonVolume);
            }
        }
        return ret;
    }

    protected @Nullable String getFromTags(List<Tag> tagList, String key)
    {
        String ret = null;
        for (Tag tag : tagList)
        {
            if (tag.getKey().equals(key))
            {
                ret = tag.getValue();
                break;
            }
        }
        return ret;
    }

    @SuppressWarnings("unchecked")
    protected String asGenericLvIdentifier(EbsData<?> vlmData)
    {
        String vlmDataLvId;
        if (vlmData.getVolume() instanceof Volume)
        {
            vlmDataLvId = asLvIdentifier((EbsData<Resource>) vlmData);
        }
        else
        {
            vlmDataLvId = asSnapLvIdentifier((EbsData<Snapshot>) vlmData);
        }
        return vlmDataLvId;
    }

    @Override
    protected String asLvIdentifier(
        StorPoolName spName,
        ResourceName resourceNameRef,
        String rscNameSuffixRef,
        VolumeNumber volumeNumberRef
    )
    {
        // this lvIdentifier does not correspond to the actual /dev/<whatever> device, but we can come up with an
        // arbitrary ID here as long as we keep track of the mapping of ID -> Pair<EBS-vol-id, "/dev/<whatever>">
        return String.format(
            FORMAT_RSC_TO_LVM_ID,
            spName.displayValue,
            resourceNameRef.displayValue,
            rscNameSuffixRef,
            volumeNumberRef.value
        );
    }

    @Override
    protected String asSnapLvIdentifierRaw(
        String spNameRef,
        String rscNameRef,
        String rscNameSuffixRef,
        String snapNameRef,
        int vlmNrRef
    )
    {
        return String.format(
            FORMAT_SNAP_TO_LVM_ID,
            spNameRef,
            rscNameRef,
            rscNameSuffixRef,
            vlmNrRef,
            snapNameRef
        );
    }

    @Override
    protected void setAllocatedSize(EbsData<Resource> vlmDataRef, long sizeRef) throws DatabaseException
    {
        vlmDataRef.setAllocatedSize(sizeRef);
    }

    @Override
    protected void setUsableSize(EbsData<Resource> vlmDataRef, long sizeRef) throws DatabaseException
    {
        vlmDataRef.setUsableSize(sizeRef);
    }

    @Override
    protected void setExpectedUsableSize(EbsData<Resource> vlmDataRef, long sizeRef)
        throws DatabaseException, StorageException
    {
        vlmDataRef.setExepectedSize(sizeRef);
    }

    @Override
    protected boolean updateDmStats()
    {
        return false;
    }

    @Override
    public SpaceInfo getSpaceInfo(StorPool storPoolRef) throws StorageException, AccessDeniedException
    {
        return ENOUGH_SPACE_INFO;
    }

    @Override
    protected String getStorageName(EbsData<Resource> vlmDataRef)
        throws DatabaseException, AccessDeniedException, StorageException
    {
        return getStorageName(vlmDataRef.getStorPool());
    }

    @Override
    protected String getStorageName(StorPool storPoolRef) throws AccessDeniedException, StorageException
    {
        String poolName;
        try
        {
            poolName = DeviceLayerUtils.getNamespaceStorDriver(
                storPoolRef.getProps(storDriverAccCtx)
            )
                .getProp(ApiConsts.KEY_STOR_POOL_NAME);
        }
        catch (InvalidKeyException | AccessDeniedException exc)
        {
            throw new ImplementationError(exc);
        }
        return poolName;
    }

    protected void setEbsVlmId(EbsData<Resource> vlmDataRef, String ebsVlmIdRef)
        throws AccessDeniedException, DatabaseException
    {
        try
        {
            vlmDataRef.getVolume().getProps(storDriverAccCtx).setProp(
                InternalApiConsts.KEY_EBS_VLM_ID + vlmDataRef.getRscLayerObject().getResourceNameSuffix(),
                ebsVlmIdRef,
                ApiConsts.NAMESPC_STLT + "/" + ApiConsts.NAMESPC_EBS
            );
        }
        catch (InvalidKeyException | InvalidValueException exc)
        {
            throw new ImplementationError(exc);
        }
    }

    protected String getEbsVlmId(EbsData<?> vlmDataRef)
        throws AccessDeniedException
    {
        String ret;
        try
        {
            ret = vlmDataRef.getVolume().getProps(storDriverAccCtx).getProp(
                InternalApiConsts.KEY_EBS_VLM_ID + vlmDataRef.getRscLayerObject().getResourceNameSuffix(),
                ApiConsts.NAMESPC_STLT + "/" + ApiConsts.NAMESPC_EBS
            );
        }
        catch (InvalidKeyException exc)
        {
            throw new ImplementationError(exc);
        }
        return ret;
    }

    protected abstract EbsRemote getEbsRemote(StorPool storPoolRef) throws AccessDeniedException;

}
