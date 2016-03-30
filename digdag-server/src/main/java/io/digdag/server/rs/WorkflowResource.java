package io.digdag.server.rs;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.function.Supplier;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.PUT;
import javax.ws.rs.POST;
import javax.ws.rs.GET;
import com.google.inject.Inject;
import com.google.common.base.Throwables;
import com.google.common.collect.*;
import com.google.common.io.ByteStreams;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import io.digdag.client.config.Config;
import io.digdag.client.config.ConfigFactory;
import io.digdag.core.workflow.*;
import io.digdag.core.repository.*;
import io.digdag.core.schedule.*;
import io.digdag.core.config.YamlConfigLoader;
import io.digdag.spi.ScheduleTime;
import io.digdag.spi.Scheduler;
import io.digdag.client.api.*;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

@Path("/")
@Produces("application/json")
public class WorkflowResource
    extends AuthenticatedResource
{
    // [*] GET  /api/workflow?repository=<name>&name=<name>      # lookup a workflow of the latest revision of a repository by name
    // [*] GET  /api/workflow?repository=<name>&revision=<name>&name=<name>  # lookup a workflow of a past revision of a repository by name
    // [*] GET  /api/workflows/{id}                              # get a workflow
    // [*] GET  /api/workflows/{id}/truncated_session_time       # truncate a time based on timzeone of this workflow

    private final RepositoryStoreManager rm;
    private final ScheduleStoreManager sm;
    private final SchedulerManager srm;

    @Inject
    public WorkflowResource(
            RepositoryStoreManager rm,
            ScheduleStoreManager sm,
            SchedulerManager srm)
    {
        this.rm = rm;
        this.sm = sm;
        this.srm = srm;
    }

    @GET
    @Path("/api/workflow")
    public RestWorkflowDefinition getWorkflowDefinition(
            @QueryParam("repository") String repoName,
            @QueryParam("revision") String revName,
            @QueryParam("name") String wfName)
        throws ResourceNotFoundException
    {
        Preconditions.checkArgument(repoName != null, "repository= is required");
        Preconditions.checkArgument(wfName != null, "name= is required");

        RepositoryStore rs = rm.getRepositoryStore(getSiteId());
        StoredRepository repo = rs.getRepositoryByName(repoName);
        StoredRevision rev;
        if (revName == null) {
            rev = rs.getLatestRevision(repo.getId());
        }
        else {
            rev = rs.getRevisionByName(repo.getId(), revName);
        }
        StoredWorkflowDefinition def = rs.getWorkflowDefinitionByName(rev.getId(), wfName);
        return RestModels.workflowDefinition(repo, rev, def);
    }

    @GET
    @Path("/api/workflows/{id}")
    public RestWorkflowDefinition getWorkflowDefinition(@PathParam("id") long id)
        throws ResourceNotFoundException
    {
        StoredWorkflowDefinitionWithRepository def =
            rm.getRepositoryStore(getSiteId())
            .getWorkflowDefinitionById(id);
        return RestModels.workflowDefinition(def);
    }

    @GET
    @Path("/api/workflows/{id}/truncated_session_time")
    public RestWorkflowSessionTime getWorkflowDefinition(
            @PathParam("id") long id,
            @QueryParam("session_time") LocalTimeOrInstant localTime,
            @QueryParam("mode") SessionTimeTruncate mode)
        throws ResourceNotFoundException
    {
        Preconditions.checkArgument(localTime != null, "session_time= is required");

        StoredWorkflowDefinitionWithRepository def =
            rm.getRepositoryStore(getSiteId())
            .getWorkflowDefinitionById(id);

        ZoneId timeZone = def.getTimeZone();

        Instant truncated;
        if (mode != null) {
            truncated = truncateSessionTime(
                    localTime.toInstant(timeZone),
                    timeZone, () -> srm.tryGetScheduler(def), mode);
        }
        else {
            truncated = localTime.toInstant(timeZone);
        }

        return RestModels.workflowSessionTime(
                def, truncated, timeZone);
    }

    private Instant truncateSessionTime(
            Instant sessionTime,
            ZoneId timeZone,
            Supplier<Optional<Scheduler>> schedulerSupplier,
            SessionTimeTruncate mode)
    {
        switch (mode) {
        case HOUR:
            return ZonedDateTime.ofInstant(sessionTime, timeZone)
                .truncatedTo(ChronoUnit.HOURS)
                .toInstant();
        case DAY:
            return ZonedDateTime.ofInstant(sessionTime, timeZone)
                .truncatedTo(ChronoUnit.DAYS)
                .toInstant();
        default:
            {
                Optional<Scheduler> scheduler = schedulerSupplier.get();
                if (!scheduler.isPresent()) {
                    throw new IllegalArgumentException("session_time_truncate=" + mode + " is set but _schedule is not set to this workflow");
                }
                switch (mode) {
                case SCHEDULE:
                    return scheduler.get().getFirstScheduleTime(sessionTime).getTime();
                case NEXT_SCHEDULE:
                    return scheduler.get().nextScheduleTime(sessionTime).getTime();
                default:
                    throw new IllegalArgumentException();
                }
            }
        }
    }
}
