package com.linbit.linstor.api.protobuf;

import com.linbit.ChildProcessTimeoutException;
import com.linbit.extproc.ExtCmd;
import com.linbit.extproc.ExtCmdFactory;
import com.linbit.linstor.InternalApiConsts;
import com.linbit.linstor.api.ApiCall;
import com.linbit.linstor.api.interfaces.serializer.CommonSerializer;
import com.linbit.linstor.core.LinStor;
import com.linbit.linstor.core.UpdateMonitor;
import com.linbit.linstor.core.apicallhandler.StltApiCallHandler;
import com.linbit.linstor.core.apicallhandler.satellite.authentication.AuthenticationResult;
import com.linbit.linstor.core.cfg.StltConfig;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.netcom.Peer;
import com.linbit.linstor.proto.javainternal.c2s.MsgIntAuthOuterClass.MsgIntAuth;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

@ProtobufApiCall(
    name = InternalApiConsts.API_AUTH,
    description = "Called by the controller to authenticate the controller to the satellite",
    requiresAuth = false
)
@Singleton
public class CtrlAuth implements ApiCall
{
    private final ErrorReporter errorReporter;
    private final StltApiCallHandler apiCallHandler;
    private final ApiCallAnswerer apiCallAnswerer;
    private final CommonSerializer commonSerializer;
    private final UpdateMonitor updateMonitor;
    private final Provider<Peer> controllerPeerProvider;
    private final ExtCmdFactory extCmdFactory;
    private final StltConfig stltConfig;

    @Inject
    public CtrlAuth(
        ErrorReporter errorReporterRef,
        StltApiCallHandler apiCallHandlerRef,
        ApiCallAnswerer apiCallAnswererRef,
        CommonSerializer commonSerializerRef,
        UpdateMonitor updateMonitorRef,
        Provider<Peer> controllerPeerProviderRef,
        ExtCmdFactory extCmdFactoryRef,
        StltConfig stltConfigRef
    )
    {
        errorReporter = errorReporterRef;
        apiCallHandler = apiCallHandlerRef;
        apiCallAnswerer = apiCallAnswererRef;
        commonSerializer = commonSerializerRef;
        updateMonitor = updateMonitorRef;
        controllerPeerProvider = controllerPeerProviderRef;
        extCmdFactory = extCmdFactoryRef;
        stltConfig = stltConfigRef;
    }

    @Override
    public void execute(InputStream msgDataIn)
        throws IOException
    {
        // get the host uname for the drbd config
        String nodeUname = "";
        try
        {
            ExtCmd.OutputData out = extCmdFactory.create().exec("uname", "-n");
            nodeUname = new String(out.stdoutData).trim();
        }
        catch (ChildProcessTimeoutException | IOException exc)
        {
            errorReporter.reportError(exc);
        }

        // TODO: implement authentication
        MsgIntAuth auth = MsgIntAuth.parseDelimitedFrom(msgDataIn);
        String nodeName = auth.getNodeName();
        UUID nodeUuid = UUID.fromString(auth.getNodeUuid());

        Peer controllerPeer = controllerPeerProvider.get();
        AuthenticationResult authResult =
            apiCallHandler.authenticate(nodeUuid, nodeName, controllerPeer);

        byte[] replyBytes;
        if (authResult.isAuthenticated())
        {
            // all ok, send the new fullSyncId with the AUTH_ACCEPT msg
            // additionally we also send information which layers are supported by the current satellite

            replyBytes = commonSerializer.headerlessBuilder()
                .authSuccess(
                    updateMonitor.getNextFullSyncId(),
                    LinStor.VERSION_INFO_PROVIDER.getSemanticVersion(),
                    nodeUname,
                    authResult.getExternalToolsInfoList(),
                    authResult.getApiCallRc(),
                    stltConfig.getConfigDir(),
                    stltConfig.isDebugConsoleEnabled(),
                    stltConfig.isLogPrintStackTrace(),
                    stltConfig.getLogDirectory(),
                    stltConfig.getLogLevel(),
                    stltConfig.getLogLevelLinstor(),
                    stltConfig.getStltOverrideNodeName(),
                    stltConfig.isOpenflex(),
                    stltConfig.isRemoteSpdk(),
                    stltConfig.isEbs(),
                    stltConfig.getDrbdKeepResPattern(),
                    stltConfig.getNetBindAddress(),
                    stltConfig.getNetPort(),
                    stltConfig.getNetType()
                )
                .build();
        }
        else
        {
            // whatever happened should be in the apiCallRc
            replyBytes = commonSerializer.headerlessBuilder()
                .authError(authResult.getApiCallRc())
                .build();
        }
        controllerPeerProvider.get().sendMessage(
            apiCallAnswerer.answerBytes(
                replyBytes,
                InternalApiConsts.API_AUTH_RESPONSE
            ),
            InternalApiConsts.API_AUTH_RESPONSE
        );
    }
}
