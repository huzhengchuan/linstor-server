package com.linbit.linstor.backupshipping;

import com.linbit.ImplementationError;
import com.linbit.extproc.ExtCmdFactory;
import com.linbit.linstor.InternalApiConsts;
import com.linbit.linstor.annotation.SystemContext;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.api.BackupToS3;
import com.linbit.linstor.api.interfaces.serializer.CtrlStltSerializer;
import com.linbit.linstor.core.ControllerPeerConnector;
import com.linbit.linstor.core.CoreModule.RemoteMap;
import com.linbit.linstor.core.StltConfigAccessor;
import com.linbit.linstor.core.StltConnTracker;
import com.linbit.linstor.core.StltSecurityObjects;
import com.linbit.linstor.core.objects.Remote;
import com.linbit.linstor.core.objects.Remote.RemoteType;
import com.linbit.linstor.core.objects.S3Remote;
import com.linbit.linstor.core.objects.Snapshot;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.propscon.InvalidKeyException;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.storage.data.provider.AbsStorageVlmData;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.io.IOException;
import java.text.ParseException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.amazonaws.SdkClientException;

@Singleton
public class BackupShippingS3Service extends AbsBackupShippingService
{
    public static final String SERVICE_INFO = "BackupShippingS3Service";
    private static final Pattern S3_BACKUP_NAME_PATTERN = Pattern.compile(
        "^([a-zA-Z0-9_-]{2,48})_(back_[0-9]{8}_[0-9]{6})$"
    );

    protected static final String CMD_FORMAT_SENDING =
        "trap 'kill -HUP 0' SIGTERM; " +
        "(" +
            "%s | " +  // thin_send prev_LV_snapshot cur_LV_snapshot
            // "pv -s 100m -bnr -i 0.1 | " +
            "zstd;" +
        ")&\\wait $!";

    protected static final String CMD_FORMAT_RECEIVING = "trap 'kill -HUP 0' SIGTERM; " +
        "exec 7<&0 0</dev/null; " +
        "set -o pipefail; " +
        "(" +
        "exec 0<&7 7<&-; zstd -d | " +
        // "pv -s 100m -bnr -i 0.1 | " +
        "%s ;" +
        ") & wait $!";

    private final BackupToS3 backupHandler;

    @Inject
    public BackupShippingS3Service(
        BackupToS3 backupHandlerRef,
        ErrorReporter errorReporterRef,
        ExtCmdFactory extCmdFactoryRef,
        ControllerPeerConnector controllerPeerConnectorRef,
        CtrlStltSerializer interComSerializerRef,
        @SystemContext AccessContext accCtxRef,
        StltSecurityObjects stltSecObjRef,
        StltConfigAccessor stltConfigAccessorRef,
        StltConnTracker stltConnTracker,
        RemoteMap remoteMapRef
    )
    {
        super(
            errorReporterRef,
            SERVICE_INFO,
            RemoteType.S3,
            extCmdFactoryRef,
            controllerPeerConnectorRef,
            interComSerializerRef,
            accCtxRef,
            stltSecObjRef,
            stltConfigAccessorRef,
            stltConnTracker,
            remoteMapRef
        );

        backupHandler = backupHandlerRef;

        // this causes all shippings to be aborted should the satellite lose connection to the controller
        stltConnTracker.addClosingListener(this::killAllShipping);
    }

    @Override
    protected String getCommandReceiving(String cmdRef, Remote ignoredRemote)
    {
        return String.format(CMD_FORMAT_RECEIVING, cmdRef);
    }

    @Override
    protected String getCommandSending(String cmdRef, Remote ignoredRemote)
    {
        return String.format(CMD_FORMAT_SENDING, cmdRef);
    }

    @Override
    protected String getBackupNameForRestore(AbsStorageVlmData<Snapshot> snapVlmDataRef)
        throws InvalidKeyException, AccessDeniedException
    {
        String ret;

        String simpleBackupName = snapVlmDataRef.getVolume().getAbsResource().getProps(accCtx).getProp(
            InternalApiConsts.KEY_BACKUP_TO_RESTORE,
            ApiConsts.NAMESPC_BACKUP_SHIPPING
        );

        Matcher m = S3_BACKUP_NAME_PATTERN.matcher(simpleBackupName);
        if (m.matches())
        {
            ret = String.format(
                S3Consts.BACKUP_KEY_FORMAT,
                m.group(1),
                snapVlmDataRef.getRscLayerObject().getResourceNameSuffix(),
                snapVlmDataRef.getVlmNr().value,
                m.group(2)
            );
        }
        else
        {
            throw new ImplementationError(
                "The simplified backup-name " + simpleBackupName + " does not conform to the expected format."
            );
        }

        return ret;
    }

    @Override
    protected BackupShippingDaemon createDaemon(AbsStorageVlmData<Snapshot> snapVlmDataRef,
        String shippingDescrRef,
        String[] fullCommand,
        String backupNameRef,
        Remote remote,
        boolean restore,
        Consumer<Boolean> postAction
    )
    {
        return new BackupShippingS3Daemon(
            errorReporter,
            threadGroup,
            "shipping_" + shippingDescrRef,
            fullCommand,
            backupNameRef,
            (S3Remote) remote,
            backupHandler,
            restore,
            snapVlmDataRef.getAllocatedSize(),
            postAction,
            accCtx,
            stltSecObj.getCryptKey()
        );
    }

    @Override
    protected void preCtrlNotifyBackupShipped(
        boolean success,
        boolean restoring,
        Snapshot snap,
        ShippingInfo shippingInfo
    )
    {
        if (success && !restoring)
        {
            try
            {
                S3Remote s3Remote = (S3Remote) shippingInfo.remote;

                backupHandler.putObject(
                    shippingInfo.s3MetaKey,
                    fillPojo(snap, shippingInfo.basedOnS3MetaKey),
                    s3Remote,
                    accCtx,
                    stltSecObj.getCryptKey()
                );
            }
            catch (InvalidKeyException | AccessDeniedException | IOException | ParseException exc)
            {
                errorReporter.reportError(new ImplementationError(exc));
                success = false;
            }
            catch (SdkClientException exc)
            {
                errorReporter.reportError(exc);
                success = false;
            }
        }
    }
}
