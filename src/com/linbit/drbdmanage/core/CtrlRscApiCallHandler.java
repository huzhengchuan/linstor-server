package com.linbit.drbdmanage.core;

import static com.linbit.drbdmanage.api.ApiConsts.*;
import static com.linbit.drbdmanage.ApiCallRcConstants.*;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.linbit.ImplementationError;
import com.linbit.InvalidNameException;
import com.linbit.TransactionMgr;
import com.linbit.ValueOutOfRangeException;
import com.linbit.drbdmanage.ApiCallRc;
import com.linbit.drbdmanage.ApiCallRcImpl;
import com.linbit.drbdmanage.DrbdDataAlreadyExistsException;
import com.linbit.drbdmanage.NodeData;
import com.linbit.drbdmanage.NodeId;
import com.linbit.drbdmanage.NodeName;
import com.linbit.drbdmanage.PriorityProps;
import com.linbit.drbdmanage.Resource.RscFlags;
import com.linbit.drbdmanage.ResourceData;
import com.linbit.drbdmanage.ResourceDefinitionData;
import com.linbit.drbdmanage.ResourceName;
import com.linbit.drbdmanage.StorPool;
import com.linbit.drbdmanage.StorPoolData;
import com.linbit.drbdmanage.StorPoolDefinition;
import com.linbit.drbdmanage.StorPoolDefinitionData;
import com.linbit.drbdmanage.StorPoolName;
import com.linbit.drbdmanage.Volume.VlmApi;
import com.linbit.drbdmanage.Volume.VlmFlags;
import com.linbit.drbdmanage.VolumeData;
import com.linbit.drbdmanage.VolumeDefinition;
import com.linbit.drbdmanage.VolumeNumber;
import com.linbit.drbdmanage.ApiCallRcImpl.ApiCallRcEntry;
import com.linbit.drbdmanage.netcom.Peer;
import com.linbit.drbdmanage.propscon.InvalidKeyException;
import com.linbit.drbdmanage.propscon.Props;
import com.linbit.drbdmanage.security.AccessContext;
import com.linbit.drbdmanage.security.AccessDeniedException;

class CtrlRscApiCallHandler
{
    private final Controller controller;

    CtrlRscApiCallHandler(Controller controllerRef)
    {
        controller = controllerRef;
    }

    public ApiCallRc createResource(
        AccessContext accCtx,
        Peer client,
        String nodeNameStr,
        String rscNameStr,
        int nodeIdRaw,
        Map<String, String> rscPropsMap,
        List<VlmApi> vlmApiList
    )
    {
        ApiCallRcImpl apiCallRc = new ApiCallRcImpl();


        TransactionMgr transMgr = null;

        NodeName nodeName = null;
        ResourceName rscName = null;

        NodeData node = null;
        ResourceDefinitionData rscDfn = null;

        NodeId nodeId = null;

        ResourceData rsc = null;
        VlmApi currentVlmApi = null;
        VolumeNumber volNr = null;
        VolumeDefinition vlmDfn = null;
        String storPoolNameStr = null;
        Props vlmProps = null;
        Props rscProps = null;
        Props nodeProps = null;
        StorPoolName storPoolName = null;
        StorPoolDefinition storPoolDfn = null;
        StorPool storPool = null;

        try
        {
            transMgr = new TransactionMgr(controller.dbConnPool);

            nodeName = new NodeName(nodeNameStr); // invalidNameExc1
            rscName = new ResourceName(rscNameStr); // invalidNameExc2

            node = NodeData.getInstance( // accDeniedExc1, dataAlreadyExistsExc0
                accCtx,
                nodeName,
                null,
                null,
                transMgr,
                false,
                false
            );
            rscDfn = ResourceDefinitionData.getInstance( // accDeniedExc2, dataAlreadyExistsExc0
                accCtx,
                rscName,
                null,
                transMgr,
                false,
                false
            );

            if (node == null)
            {
                ApiCallRcEntry nodeNotFoundEntry = new ApiCallRcEntry();
                nodeNotFoundEntry.setReturnCode(RC_RSC_CRT_FAIL_NOT_FOUND_NODE);
                nodeNotFoundEntry.setCauseFormat(String.format(
                    "The specified node '%s' could not be found in the database",
                    nodeNameStr
                ));
                nodeNotFoundEntry.setCorrectionFormat(
                    String.format(
                        "Create a node with the name '%s' first.",
                        nodeNameStr
                    )
                );
                nodeNotFoundEntry.putVariable(KEY_NODE_NAME, nodeNameStr);
                nodeNotFoundEntry.putObjRef(KEY_NODE, nodeNameStr);
                nodeNotFoundEntry.putObjRef(KEY_RSC_DFN, rscNameStr);

                apiCallRc.addEntry(nodeNotFoundEntry);
            }
            else
            if (rscDfn == null)
            {
                ApiCallRcEntry rscDfnNotFoundEntry = new ApiCallRcEntry();
                rscDfnNotFoundEntry.setReturnCode(RC_RSC_CRT_FAIL_NOT_FOUND_RSC_DFN);
                rscDfnNotFoundEntry.setCauseFormat(String.format(
                    "The specified resource definition '%s' could not be found in the database",
                    rscNameStr
                ));
                rscDfnNotFoundEntry.setCorrectionFormat(
                    String.format(
                        "Create a resource definition with the name '%s' first.",
                        rscNameStr
                    )
                );
                rscDfnNotFoundEntry.putVariable(KEY_RSC_NAME, rscNameStr);
                rscDfnNotFoundEntry.putObjRef(KEY_NODE, nodeNameStr);
                rscDfnNotFoundEntry.putObjRef(KEY_RSC_DFN, rscNameStr);

                apiCallRc.addEntry(rscDfnNotFoundEntry);
            }
            else
            {
                nodeId = new NodeId(nodeIdRaw); // valueOutOfRangeExc1

                RscFlags[] initFlags = null;

                ApiCallRcImpl successApiCallRc = new ApiCallRcImpl();
                boolean success = true;

                rsc = ResourceData.getInstance( // accDeniedExc3, dataAlreadyExistsExc1
                    accCtx,
                    rscDfn,
                    node,
                    nodeId,
                    initFlags,
                    transMgr,
                    true,
                    true
                );

                ApiCallRcEntry rscSuccess = new ApiCallRcEntry();
                String rscSuccessMsg = String.format(
                    "Resource '%s' successfully created on node '%s'",
                    rscNameStr,
                    nodeNameStr
                );

                rscSuccess.setMessageFormat(rscSuccessMsg);
                rscSuccess.setReturnCode(RC_RSC_CREATED);
                rscSuccess.putObjRef(KEY_NODE, nodeNameStr);
                rscSuccess.putObjRef(KEY_RSC_DFN, rscNameStr);
                rscSuccess.putVariable(KEY_NODE_NAME, nodeNameStr);
                rscSuccess.putVariable(KEY_RSC_NAME, rscNameStr);

                successApiCallRc.addEntry(rscSuccess);

                for (VlmApi vlmApi : vlmApiList)
                {
                    currentVlmApi = vlmApi;

                    volNr = null;
                    vlmDfn = null;
                    storPoolName = null;
                    storPoolDfn = null;
                    storPool = null;
                    vlmProps = null;
                    rscProps = null;
                    nodeProps = null;

                    volNr = new VolumeNumber(vlmApi.getVlmNr()); // valueOutOfRangeExc2
                    vlmDfn = rscDfn.getVolumeDfn(accCtx, volNr); // accDeniedExc4

                    storPoolNameStr = vlmApi.getStorPoolName();
                    if (storPoolNameStr == null)
                    {
                        vlmProps = vlmDfn.getProps(accCtx); // accDeniedExc5
                        rscProps = rsc.getProps(accCtx); // accDeniedExc6
                        nodeProps = node.getProps(accCtx); // accDeniedExc7
                        PriorityProps prioProps = new PriorityProps(
                            vlmProps,
                            rscProps,
                            nodeProps
                        );
                        storPoolNameStr = prioProps.getProp(KEY_STOR_POOL_NAME);
                    }
                    if  (storPoolNameStr == null)
                    {
                        storPoolNameStr = controller.getDefaultStorPoolName();
                    }

                    storPoolName = new StorPoolName(storPoolNameStr); // invalidNameExc3

                    storPoolDfn = StorPoolDefinitionData.getInstance( // accDeniedExc8, dataAlreadyExistsExc0
                        accCtx,
                        storPoolName,
                        transMgr,
                        false,
                        false
                    );
                    if (storPoolDfn != null)
                    {
                        storPool = StorPoolData.getInstance( // accDeniedExc9, dataAlreadyExistsExc0
                            accCtx,
                            node,
                            storPoolDfn,
                            null,
                            transMgr,
                            false,
                            false,
                            false
                        );
                    }
                    if (storPoolDfn == null || storPool == null)
                    {
                        success = false;

                        ApiCallRcEntry entry = new ApiCallRcEntry();
                        String errorMessage;
                        if ( storPoolDfn == null)
                        {
                            errorMessage = String.format(
                                "Storage pool definition '%s' could not be found.",
                                storPoolNameStr
                            );
                            entry.setReturnCode(RC_RSC_CRT_FAIL_NOT_FOUND_STOR_POOL_DFN);
                        }
                        else
                        {
                            errorMessage = String.format(
                                "Storage pool '%s' on node '%s' could not be found.",
                                storPoolNameStr,
                                nodeNameStr
                            );
                            entry.setReturnCode(RC_RSC_CRT_FAIL_NOT_FOUND_STOR_POOL);
                        }
                        controller.getErrorReporter().reportError(
                            null,
                            accCtx,
                            client,
                            errorMessage
                        );
                        entry.setMessageFormat(errorMessage);
                        entry.putObjRef(KEY_NODE, nodeNameStr);
                        entry.putObjRef(KEY_RSC_DFN, rscNameStr);
                        entry.putObjRef(KEY_VLM_NR, Integer.toString(volNr.value));
                        entry.putVariable(KEY_NODE_NAME, nodeNameStr);
                        entry.putVariable(KEY_RSC_NAME, rscNameStr);
                        entry.putVariable(KEY_VLM_NR, Integer.toString(volNr.value));
                        break;
                    }
                    else
                    {
                        VlmFlags[] vlmFlags = null;

                        VolumeData.getInstance( // accDeniedExc10, dataAlreadyExistsExc2
                            accCtx,
                            rsc,
                            vlmDfn,
                            storPool,
                            vlmApi.getBlockDevice(),
                            vlmApi.getMetaDisk(),
                            vlmFlags,
                            transMgr,
                            true,
                            true
                        );
                        ApiCallRcEntry vlmSuccess = new ApiCallRcEntry();
                        vlmSuccess.setMessageFormat(
                            String.format(
                                "Volume with number %d created successfully on node '%s' for resource '%s'.",
                                vlmApi.getVlmNr(),
                                nodeNameStr,
                                rscNameStr
                            )
                        );
                        vlmSuccess.putVariable(KEY_NODE_NAME, nodeNameStr);
                        vlmSuccess.putVariable(KEY_RSC_NAME, rscNameStr);
                        vlmSuccess.putVariable(KEY_VLM_NR, Integer.toString(vlmApi.getVlmNr()));
                        vlmSuccess.putObjRef(KEY_NODE, nodeNameStr);
                        vlmSuccess.putObjRef(KEY_RSC_DFN, rscNameStr);
                        vlmSuccess.putObjRef(KEY_VLM_NR, Integer.toString(vlmApi.getVlmNr()));

                        successApiCallRc.addEntry(vlmSuccess);
                    }
                }

                if (success)
                {
                    transMgr.commit();

                    // if everything worked fine, just replace the returned rcApiCall with the
                    // already filled successApiCallRc. otherwise, this line does not get executed anyways
                    apiCallRc = successApiCallRc;
                    controller.getErrorReporter().logInfo(rscSuccessMsg);

                    // TODO: tell satellite(s) to do their job
                    // TODO: if a satellite confirms creation, also log it to controller.info
                }
            }
        }
        catch (SQLException sqlExc)
        {
            String errorMessage = String.format(
                "A database error occured while trying to create the resource '%s' on node '%s'.",
                nodeNameStr,
                rscNameStr
            );
            controller.getErrorReporter().reportError(
                sqlExc,
                accCtx,
                client,
                errorMessage
            );

            ApiCallRcEntry entry = new ApiCallRcEntry();
            entry.setReturnCodeBit(RC_RSC_CRT_FAIL_SQL);
            entry.setMessageFormat(errorMessage);
            entry.setCauseFormat(sqlExc.getMessage());
            entry.putObjRef(KEY_NODE, nodeNameStr);
            entry.putObjRef(KEY_RSC_DFN, rscNameStr);
            entry.putVariable(KEY_NODE_NAME, nodeNameStr);
            entry.putVariable(KEY_RSC_NAME, rscNameStr);

            apiCallRc.addEntry(entry);
        }
        catch (InvalidNameException invalidNameExc)
        {
            ApiCallRcEntry entry = new ApiCallRcEntry();
            String errorMessage;
            if (nodeName == null)
            { // invalidNameExc1
                errorMessage = String.format("Given node name '%s' is invalid.", nodeNameStr);
                entry.putVariable(KEY_NODE_NAME, nodeNameStr);
                entry.setReturnCodeBit(RC_RSC_CRT_FAIL_INVALID_NODE_NAME);
            }
            else
            if (rscName == null)
            { // invalidNameExc2
                errorMessage = String.format("Given resource name '%s' is invalid.", rscNameStr);
                entry.putVariable(KEY_RSC_NAME, rscNameStr);
                entry.setReturnCodeBit(RC_RSC_CRT_FAIL_INVALID_RSC_NAME);
            }
            else
            { // invalidNameExc3
                errorMessage = String.format("Given storage pool name '%s' is invalid.", storPoolNameStr);
                entry.putVariable(KEY_STOR_POOL_NAME, storPoolNameStr);
                entry.setReturnCodeBit(RC_RSC_CRT_FAIL_INVALID_STOR_POOL_NAME);

            }
            controller.getErrorReporter().reportError(
                invalidNameExc,
                accCtx,
                client,
                errorMessage
            );
            entry.setMessageFormat(errorMessage);
            entry.setCauseFormat(invalidNameExc.getMessage());
            entry.putObjRef(KEY_NODE, nodeNameStr);
            entry.putObjRef(KEY_RSC_DFN, rscNameStr);

            apiCallRc.addEntry(entry);
        }
        catch (AccessDeniedException accDeniedExc)
        {
            ApiCallRcEntry entry = new ApiCallRcEntry();
            String action = "Given user has no permission to ";
            if (node == null)
            { // accDeniedExc1
                action += String.format(
                    "access the node '%s'.",
                    nodeNameStr
                );
                entry.putVariable(KEY_NODE_NAME, nodeNameStr);
                entry.setReturnCodeBit(RC_RSC_CRT_FAIL_ACC_DENIED_NODE);
            }
            else
            if (rscDfn == null)
            { // accDeniedExc2
                action += String.format(
                    "access the resource definition '%s'.",
                    rscNameStr
                );
                entry.putVariable(KEY_RSC_NAME, rscNameStr);
                entry.setReturnCodeBit(RC_RSC_CRT_FAIL_ACC_DENIED_RSC_DFN);
            }
            else
            if (rsc == null)
            { // accDeniedExc3
                action += String.format(
                    "access the resource '%s' on node '%s'.",
                    rscNameStr,
                    nodeNameStr
                );
                entry.putVariable(KEY_NODE_NAME, nodeNameStr);
                entry.putVariable(KEY_RSC_NAME, rscNameStr);
                entry.setReturnCodeBit(RC_RSC_CRT_FAIL_ACC_DENIED_RSC);
            }
            else
            if (vlmDfn == null)
            { // accDeniedExc4
                action += String.format(
                    "access the volume definition with volume number %d on resource '%s' on node '%s'.",
                    currentVlmApi.getVlmNr(),
                    rscNameStr,
                    nodeNameStr
                );
                entry.putVariable(KEY_NODE_NAME, nodeNameStr);
                entry.putVariable(KEY_RSC_NAME, rscNameStr);
                entry.putVariable(KEY_VLM_NR, Integer.toString(currentVlmApi.getVlmNr()));
                entry.putObjRef(KEY_VLM_NR, Integer.toString(currentVlmApi.getVlmNr()));
                entry.setReturnCodeBit(RC_RSC_CRT_FAIL_ACC_DENIED_VLM_DFN);
            }
            else
            // accDeniedExc5, 6 or 7 cannot happen as those should have triggered
            // accDeniedExc4, 3 or 1 respectively
            if (storPoolDfn == null)
            { // accDeniedExc8
                action += String.format(
                    "access the storage pool definition '%s'.",
                    storPoolNameStr
                );
                entry.putVariable(KEY_STOR_POOL_NAME, storPoolNameStr);
                entry.putObjRef(KEY_STOR_POOL_DFN, storPoolNameStr);
                entry.setReturnCodeBit(RC_RSC_CRT_FAIL_ACC_DENIED_STOR_POOL_DFN);
            }
            else
            if (storPool == null)
            { // accDeniedExc9
                action += String.format(
                    "access the storage pool '%s' on node '%s'.",
                    storPoolNameStr,
                    nodeNameStr
                );
                entry.putVariable(KEY_NODE_NAME, nodeNameStr);
                entry.putVariable(KEY_STOR_POOL_NAME, storPoolNameStr);
                entry.putObjRef(KEY_STOR_POOL_DFN, storPoolNameStr);
                entry.setReturnCodeBit(RC_RSC_CRT_FAIL_ACC_DENIED_STOR_POOL);
            }
            else
            { // accDeniedExc10
                action += String.format(
                    "create a new volume with volume number %d on resource '%s' on node '%s'.",
                    currentVlmApi.getVlmNr(),
                    rscNameStr,
                    nodeNameStr
                );
                entry.putVariable(KEY_NODE_NAME, nodeNameStr);
                entry.putVariable(KEY_RSC_NAME, rscNameStr);
                entry.putVariable(KEY_VLM_NR, Integer.toString(currentVlmApi.getVlmNr()));
                entry.putObjRef(KEY_VLM_NR, Integer.toString(currentVlmApi.getVlmNr()));
                entry.setReturnCodeBit(RC_RSC_CRT_FAIL_ACC_DENIED_VLM);
            }
            controller.getErrorReporter().reportError(
                accDeniedExc,
                accCtx,
                client,
                action
            );
            entry.setCauseFormat(accDeniedExc.getMessage());
            entry.setMessageFormat(action);
            entry.putObjRef(KEY_NODE, nodeNameStr);
            entry.putObjRef(KEY_RSC_DFN, rscNameStr);

            apiCallRc.addEntry(entry);
        }
        catch (DrbdDataAlreadyExistsException dataAlreadyExistsExc)
        {
            String errorMsgFormat;
            ApiCallRcEntry entry = new ApiCallRcEntry();
            // dataAlreadyExistsExc0 cannot happen
            if (rsc == null)
            { // dataAlreadyExistsExc1
                errorMsgFormat = String.format(
                    "Resource '%s' could not be created as it already exists on node '%s'.",
                    rscNameStr,
                    nodeNameStr
                );
                entry.putVariable(KEY_NODE_NAME, nodeNameStr);
                entry.putVariable(KEY_RSC_NAME, rscNameStr);
                entry.setReturnCodeBit(RC_RSC_CRT_FAIL_EXISTS_RSC);
            }
            else
            { // dataAlreadyExistsExc2
                errorMsgFormat = String.format(
                    "Volume with volume number %d could not be created as it already exists on " +
                        "resource '%s' on node '%s'.",
                    currentVlmApi.getVlmNr(),
                    rscNameStr,
                    nodeNameStr
                );
                entry.putVariable(KEY_NODE_NAME, nodeNameStr);
                entry.putVariable(KEY_RSC_NAME, rscNameStr);
                entry.putVariable(KEY_VLM_NR, Integer.toString(currentVlmApi.getVlmNr()));
                entry.putObjRef(KEY_VLM_NR, Integer.toString(currentVlmApi.getVlmNr()));
                entry.setReturnCodeBit(RC_RSC_CRT_FAIL_EXISTS_NODE);
            }

            controller.getErrorReporter().reportError(
                dataAlreadyExistsExc,
                accCtx,
                client,
                errorMsgFormat
            );
            entry.setCauseFormat(dataAlreadyExistsExc.getMessage());
            entry.setMessageFormat(errorMsgFormat);
            entry.putObjRef(KEY_NODE, nodeNameStr);
            entry.putObjRef(KEY_RSC_DFN, rscNameStr);

            apiCallRc.addEntry(entry);
        }
        catch (ValueOutOfRangeException valueOutOfRangeExc)
        {
            String errorMsgFormat;
            ApiCallRcEntry entry = new ApiCallRcEntry();

            if (nodeId == null)
            { // valueOutOfRangeExc1
                errorMsgFormat = String.format(
                    "Node id's value %d is out of its valid range (%d - %d)",
                    nodeIdRaw,
                    NodeId.NODE_ID_MIN,
                    NodeId.NODE_ID_MAX
                );
                entry.putVariable(KEY_NODE_ID, Integer.toString(nodeIdRaw));
                entry.setReturnCode(RC_RSC_CRT_FAIL_INVALID_NODE_ID);
            }
            else
            { // valueOutOfRangeExc2
                errorMsgFormat = String.format(
                    "Volume number %d is out of its valid range (%d - %d)",
                    currentVlmApi.getVlmNr(),
                    VolumeNumber.VOLUME_NR_MIN,
                    VolumeNumber.VOLUME_NR_MAX
                );
                entry.putVariable(KEY_VLM_NR, Integer.toString(currentVlmApi.getVlmNr()));
                entry.setReturnCode(RC_RSC_CRT_FAIL_INVALID_VLM_NR);
            }
            controller.getErrorReporter().reportError(
                valueOutOfRangeExc,
                accCtx,
                client,
                errorMsgFormat
            );
            entry.setCauseFormat(valueOutOfRangeExc.getMessage());
            entry.setMessageFormat(errorMsgFormat);
            entry.putObjRef(KEY_NODE, nodeNameStr);
            entry.putObjRef(KEY_RSC_DFN, rscNameStr);
        }
        catch (ClassNotFoundException | InstantiationException |
            IllegalAccessException | InvalidKeyException implExc)
        {
            String errorMessage;
            if (implExc instanceof InvalidKeyException)
            {
                errorMessage = String.format(
                    "The property key '%s' has thrown an InvalidKeyException "+
                        "(Node name: %s, resource name: %s)",
                    KEY_STOR_POOL_NAME,
                    nodeNameStr,
                    rscNameStr
                );
            }
            else
            {
                errorMessage = String.format(
                    "A dynamic class instantiation related exception occured while this method shoud "+
                        "not create such instances (Node name: %s, resource name: %s, volNr: %d, stor pool name: %s)",
                    KEY_STOR_POOL_NAME,
                    nodeNameStr,
                    rscNameStr,
                    volNr.value,
                    storPoolNameStr
                );
            }
            controller.getErrorReporter().reportError(
                new ImplementationError(
                    errorMessage,
                    implExc
                )
            );

            ApiCallRcEntry entry = new ApiCallRcEntry();
            entry.setReturnCodeBit(RC_RSC_CRT_FAIL_IMPL_ERROR);
            entry.setMessageFormat(
                String.format(
                    "Failed to create the resource '%s' on node '%s' due to an implementation error.",
                    rscNameStr,
                    nodeNameStr
                )
            );
            entry.setCauseFormat(implExc.getMessage());
            entry.putObjRef(KEY_RSC_DFN, rscNameStr);
            entry.putObjRef(KEY_NODE, nodeNameStr);
            entry.putVariable(KEY_RSC_NAME, rscNameStr);
            entry.putVariable(KEY_NODE_NAME, nodeNameStr);

            apiCallRc.addEntry(entry);

        }

        if (transMgr != null)
        {
            if (transMgr.isDirty())
            {
                try
                {
                    transMgr.rollback();
                }
                catch (SQLException sqlExc)
                {
                    String errorMessage = String.format(
                        "A database error occured while trying to rollback the creation of resource " +
                            "'%s' on node '%s'.",
                        rscNameStr,
                        nodeNameStr
                    );
                    controller.getErrorReporter().reportError(
                        sqlExc,
                        accCtx,
                        client,
                        errorMessage
                    );

                    ApiCallRcEntry entry = new ApiCallRcEntry();
                    entry.setReturnCodeBit(RC_RSC_CRT_FAIL_SQL_ROLLBACK);
                    entry.setMessageFormat(errorMessage);
                    entry.setCauseFormat(sqlExc.getMessage());
                    entry.putObjRef(KEY_NODE, nodeNameStr);
                    entry.putObjRef(KEY_RSC_DFN, rscNameStr);

                    apiCallRc.addEntry(entry);
                }
            }
            controller.dbConnPool.returnConnection(transMgr.dbCon);
        }
        return apiCallRc;
    }

    public ApiCallRc deleteResource(
        AccessContext accCtx,
        Peer client,
        String nodeNameStr,
        String rscNameStr
    )
    {
        ApiCallRcImpl apiCallRc = new ApiCallRcImpl();

        TransactionMgr transMgr = null;

        NodeName nodeName = null;
        ResourceName rscName = null;

        NodeData node = null;
        ResourceDefinitionData rscDfn = null;
        ResourceData rscData = null;

        try
        {
            transMgr = new TransactionMgr(controller.dbConnPool);

            nodeName = new NodeName(nodeNameStr);
            rscName = new ResourceName(rscNameStr);

            node = NodeData.getInstance(
                accCtx,
                nodeName,
                null,
                null,
                transMgr,
                false,
                false
            );
            rscDfn = ResourceDefinitionData.getInstance(
                accCtx,
                rscName,
                null,
                transMgr,
                false,
                false
            );
            rscData = ResourceData.getInstance(
                accCtx,
                rscDfn,
                node,
                null,
                null,
                transMgr,
                false,
                false
            );

            if (node == null)
            {
                ApiCallRcEntry nodeNotFoundEntry = new ApiCallRcEntry();
                nodeNotFoundEntry.setReturnCode(RC_RSC_DEL_FAIL_NOT_FOUND_NODE);
                nodeNotFoundEntry.setCauseFormat(
                    String.format(
                        "The specified node '%s' could not be found in the database.",
                        nodeNameStr
                    )
                );
                nodeNotFoundEntry.putVariable(KEY_NODE_NAME, nodeNameStr);
                nodeNotFoundEntry.putObjRef(KEY_NODE, nodeNameStr);
                nodeNotFoundEntry.putObjRef(KEY_RSC_DFN, rscNameStr);

                apiCallRc.addEntry(nodeNotFoundEntry);
            }
            else
            if (rscDfn == null)
            {
                ApiCallRcEntry rscDfnNotFoundEntry = new ApiCallRcEntry();
                rscDfnNotFoundEntry.setReturnCode(RC_RSC_DEL_FAIL_NOT_FOUND_RSC_DFN);
                rscDfnNotFoundEntry.setCauseFormat(
                    String.format(
                        "The specified resource definition '%s' could not be found in the database.",
                        rscNameStr
                    )
                );
                rscDfnNotFoundEntry.putVariable(KEY_RSC_NAME, rscNameStr);
                rscDfnNotFoundEntry.putObjRef(KEY_NODE, nodeNameStr);
                rscDfnNotFoundEntry.putObjRef(KEY_RSC_DFN, rscNameStr);

                apiCallRc.addEntry(rscDfnNotFoundEntry);
            }
            else
            if (rscData == null)
            {
                ApiCallRcEntry rscNotFoundEntry = new ApiCallRcEntry();
                rscNotFoundEntry.setReturnCode(RC_RSC_DEL_NOT_FOUND);
                rscNotFoundEntry.setCauseFormat(
                    String.format(
                        "The specified resource '%s' on node '%s' could not be found in the database.",
                        rscNameStr,
                        nodeNameStr
                    )
                );
                rscNotFoundEntry.putVariable(KEY_RSC_NAME, rscNameStr);
                rscNotFoundEntry.putVariable(KEY_NODE_NAME, nodeNameStr);
                rscNotFoundEntry.putObjRef(KEY_NODE, nodeNameStr);
                rscNotFoundEntry.putObjRef(KEY_RSC_DFN, rscNameStr);

                apiCallRc.addEntry(rscNotFoundEntry);
            }
            else
            {
                rscData.setConnection(transMgr);
                rscData.markDeleted(accCtx);
                transMgr.commit();

                ApiCallRcEntry entry = new ApiCallRcEntry();
                entry.setReturnCodeBit(RC_RSC_DELETED);
                String successMessage = String.format(
                    "Resource '%s' marked to be deleted from node '%s'.",
                    rscNameStr,
                    nodeNameStr
                );
                entry.setMessageFormat(successMessage);
                entry.putObjRef(KEY_NODE, nodeNameStr);
                entry.putObjRef(KEY_RSC_DFN, rscNameStr);
                entry.putObjRef(KEY_NODE_NAME, nodeNameStr);
                entry.putVariable(KEY_RSC_NAME, rscNameStr);
                apiCallRc.addEntry(entry);


                // TODO: tell satellites to remove all the corresponding resources
                // TODO: if satellites are finished (or no satellite had such a resource deployed)
                //       remove the rscDfn from the DB
                controller.getErrorReporter().logInfo(successMessage);
            }
        }
        catch (SQLException sqlExc)
        {
            String errorMessage = String.format(
                "A database error occured while trying to delete the resource '%s' on node '%s'.",
                nodeNameStr,
                rscNameStr
            );
            controller.getErrorReporter().reportError(
                sqlExc,
                accCtx,
                client,
                errorMessage
            );

            ApiCallRcEntry entry = new ApiCallRcEntry();
            entry.setReturnCodeBit(RC_RSC_DEL_FAIL_SQL);
            entry.setMessageFormat(errorMessage);
            entry.setCauseFormat(sqlExc.getMessage());
            entry.putObjRef(KEY_NODE, nodeNameStr);
            entry.putObjRef(KEY_RSC_DFN, rscNameStr);
            entry.putVariable(KEY_NODE_NAME, nodeNameStr);
            entry.putVariable(KEY_RSC_NAME, rscNameStr);

            apiCallRc.addEntry(entry);
        }
        catch (InvalidNameException invalidNameExc)
        {
            ApiCallRcEntry entry = new ApiCallRcEntry();
            String errorMessage;
            if (nodeName == null)
            {
                errorMessage = String.format("Given node name '%s' is invalid.", nodeNameStr);
                entry.putVariable(KEY_NODE_NAME, nodeNameStr);
                entry.setReturnCodeBit(RC_RSC_DEL_FAIL_INVALID_NODE_NAME);
            }
            else
            {
                errorMessage = String.format("Given resource name '%s' is invalid.", rscNameStr);
                entry.putVariable(KEY_RSC_NAME, rscNameStr);
                entry.setReturnCodeBit(RC_RSC_DEL_FAIL_INVALID_RSC_NAME);
            }
            controller.getErrorReporter().reportError(
                invalidNameExc,
                accCtx,
                client,
                errorMessage
            );
            entry.setMessageFormat(errorMessage);
            entry.setCauseFormat(invalidNameExc.getMessage());
            entry.putObjRef(KEY_NODE, nodeNameStr);
            entry.putObjRef(KEY_RSC_DFN, rscNameStr);

            apiCallRc.addEntry(entry);
        }
        catch (AccessDeniedException accDeniedExc)
        {
            ApiCallRcEntry entry = new ApiCallRcEntry();
            String action = "Given user has no permission to ";
            if (node == null)
            { // accDeniedExc1
                action += String.format(
                    "access the node '%s'.",
                    nodeNameStr
                );
                entry.putVariable(KEY_NODE_NAME, nodeNameStr);
                entry.setReturnCodeBit(RC_RSC_DEL_FAIL_ACC_DENIED_NODE);
            }
            else
            if (rscDfn == null)
            { // accDeniedExc2
                action += String.format(
                    "access the resource definition '%s'.",
                    rscNameStr
                );
                entry.putVariable(KEY_RSC_NAME, rscNameStr);
                entry.setReturnCodeBit(RC_RSC_DEL_FAIL_ACC_DENIED_RSC_DFN);
            }
            else
            if (rscData == null)
            { // accDeniedExc3
                action += String.format(
                    "access the resource '%s' on node '%s'.",
                    rscNameStr,
                    nodeNameStr
                );
                entry.putVariable(KEY_NODE_NAME, nodeNameStr);
                entry.putVariable(KEY_RSC_NAME, rscNameStr);
                entry.setReturnCodeBit(RC_RSC_DEL_FAIL_ACC_DENIED_RSC);
            }
            else
            { // accDeniedExc4
                action += String.format(
                    "delete the resource '%s' on node '%s'.",
                    rscNameStr,
                    nodeNameStr
                );
                entry.putVariable(KEY_NODE_NAME, nodeNameStr);
                entry.putVariable(KEY_RSC_NAME, rscNameStr);
                entry.setReturnCodeBit(RC_RSC_DEL_FAIL_ACC_DENIED_VLM_DFN);
            }
            controller.getErrorReporter().reportError(
                accDeniedExc,
                accCtx,
                client,
                action
            );
            entry.setCauseFormat(accDeniedExc.getMessage());
            entry.setMessageFormat(action);
            entry.putObjRef(KEY_NODE, nodeNameStr);
            entry.putObjRef(KEY_RSC_DFN, rscNameStr);

            apiCallRc.addEntry(entry);
        }
        catch (DrbdDataAlreadyExistsException dataAlreadyExistsExc)
        {
            controller.getErrorReporter().reportError(
                new ImplementationError(
                    String.format(
                        ".getInstance was called with failIfExists=false, still threw an AlreadyExistsException "+
                            "(Node name: %s, resource name: %s)",
                        nodeNameStr,
                        rscNameStr
                    ),
                    dataAlreadyExistsExc
                )
            );

            ApiCallRcEntry entry = new ApiCallRcEntry();
            entry.setReturnCodeBit(RC_RSC_DEL_FAIL_EXISTS_IMPL_ERROR);
            entry.setMessageFormat(
                String.format(
                    "Failed to delete the resource '%s' on node '%s' due to an implementation error.",
                    rscNameStr,
                    nodeNameStr
                )
            );
            entry.setCauseFormat(dataAlreadyExistsExc.getMessage());
            entry.putObjRef(KEY_RSC_DFN, rscNameStr);
            entry.putObjRef(KEY_NODE, nodeNameStr);
            entry.putVariable(KEY_RSC_NAME, rscNameStr);
            entry.putVariable(KEY_NODE_NAME, nodeNameStr);

            apiCallRc.addEntry(entry);
        }

        if (transMgr != null)
        {
            if (transMgr.isDirty())
            {
                try
                {
                    transMgr.rollback();
                }
                catch (SQLException sqlExc)
                {
                    String errorMessage = String.format(
                        "A database error occured while trying to rollback the deletion of " +
                            "resource '%s' on node '%s'.",
                        rscNameStr,
                        nodeNameStr
                    );
                    controller.getErrorReporter().reportError(
                        sqlExc,
                        accCtx,
                        client,
                        errorMessage
                    );

                    ApiCallRcEntry entry = new ApiCallRcEntry();
                    entry.setReturnCodeBit(RC_RSC_DEL_FAIL_SQL_ROLLBACK);
                    entry.setMessageFormat(errorMessage);
                    entry.setCauseFormat(sqlExc.getMessage());
                    entry.putObjRef(KEY_RSC_DFN, rscNameStr);
                    entry.putObjRef(KEY_NODE, nodeNameStr);
                    entry.putVariable(KEY_NODE_NAME, nodeNameStr);
                    entry.putVariable(KEY_RSC_NAME, rscNameStr);

                    apiCallRc.addEntry(entry);
                }
            }
            controller.dbConnPool.returnConnection(transMgr.dbCon);
        }

        return apiCallRc;
    }
}
