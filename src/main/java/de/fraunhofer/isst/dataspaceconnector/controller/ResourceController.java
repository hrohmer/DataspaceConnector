package de.fraunhofer.isst.dataspaceconnector.controller;

import java.net.URI;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import de.fraunhofer.iais.eis.Contract;
import de.fraunhofer.isst.dataspaceconnector.exceptions.RequestFormatException;
import de.fraunhofer.isst.dataspaceconnector.exceptions.contract.UnsupportedPatternException;
import de.fraunhofer.isst.dataspaceconnector.exceptions.resource.InvalidResourceException;
import de.fraunhofer.isst.dataspaceconnector.exceptions.resource.ResourceAlreadyExistsException;
import de.fraunhofer.isst.dataspaceconnector.exceptions.resource.ResourceException;
import de.fraunhofer.isst.dataspaceconnector.exceptions.resource.ResourceNotFoundException;
import de.fraunhofer.isst.dataspaceconnector.model.ResourceMetadata;
import de.fraunhofer.isst.dataspaceconnector.model.ResourceRepresentation;
import de.fraunhofer.isst.dataspaceconnector.services.resources.OfferedResourceServiceImpl;
import de.fraunhofer.isst.dataspaceconnector.services.resources.RequestedResourceServiceImpl;
import de.fraunhofer.isst.dataspaceconnector.services.resources.ResourceService;
import de.fraunhofer.isst.dataspaceconnector.services.usagecontrol.PolicyHandler;
import de.fraunhofer.isst.ids.framework.configuration.SerializerProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * This class provides endpoints for the internal resource handling. Resources can be created and
 * modified with it's {@link ResourceMetadata} including {@link de.fraunhofer.iais.eis.Contract} and
 * {@link ResourceRepresentation}.
 */
@RestController
@RequestMapping("/admin/api/resources")
@Tag(name = "Connector: Resource Handling", description = "Endpoints  for resource handling")
public class ResourceController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceController.class);

    private final ResourceService offeredResourceService, requestedResourceService;
    private final PolicyHandler policyHandler;


    @Autowired
    private HttpServletRequest request;
    /**
     * Constructor for ResourceController.
     *
     * @param offeredResourceService The service for the offered resources
     * @param policyHandler The service for handling policies
     * @param requestedResourceService The service for the requested resources
     * @throws IllegalArgumentException if any of the parameters is null.
     */
    @Autowired
    public ResourceController(OfferedResourceServiceImpl offeredResourceService,
        PolicyHandler policyHandler, RequestedResourceServiceImpl requestedResourceService)
        throws IllegalArgumentException {
        if (offeredResourceService == null)
            throw new IllegalArgumentException("The OfferedResourceService cannot be null.");

        if (policyHandler == null)
            throw new IllegalArgumentException("The PolicyHandler cannot be null.");

        if (requestedResourceService == null)
            throw new IllegalArgumentException("The RequestedResourceService cannot be null.");

        this.offeredResourceService = offeredResourceService;
        this.requestedResourceService = requestedResourceService;
        this.policyHandler = policyHandler;
    }

    /**
     * Registers a resource with its metadata and, if wanted, with an already existing id.
     *
     * @param resourceMetadata The resource metadata.
     * @param uuid             The resource uuid.
     * @return The added uuid.
     */
    @Operation(summary = "Register Resource", description = "Register a resource by its metadata.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Resource created, returns the UUID of the resource created", 
            	content = {@Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
            		schema = @Schema(title = "The UUID as string", type = "string"),
            		examples = {@ExampleObject(value = "6f182991-0d99-4bed-a03e-31ea8b70afad")})}),
            @ApiResponse(responseCode = "400", description = "Invalid resource",
            	content = {@Content(mediaType = MediaType.TEXT_PLAIN_VALUE, 
            		schema = @Schema(title = "The error message", type = "string"))}),
            @ApiResponse(responseCode = "409", description = "Resource already exists",
            	content = {@Content(mediaType = MediaType.TEXT_PLAIN_VALUE, 
            		schema = @Schema(title = "The error message", type = "string"))}),
            @ApiResponse(responseCode = "500", description = "Internal server error",
            	content = {@Content(mediaType = MediaType.TEXT_PLAIN_VALUE, 
            		schema = @Schema(title = "The error message", type = "string"))})})
    @RequestMapping(value = "/resource", method = RequestMethod.POST, 
    	consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public ResponseEntity<String> createResource(@RequestBody ResourceMetadata resourceMetadata,
        @RequestParam(value = "id", required = false) UUID uuid) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.setCacheControl(CacheControl.noCache());
        try {
        	
            if (uuid != null) {
                ((OfferedResourceServiceImpl) offeredResourceService).addResourceWithId(resourceMetadata, uuid);
                headers.setLocation(URI.create(request.getRequestURL().toString()).resolve(uuid.toString()));
                headers.setContentLength(uuid.toString().length());
                return new ResponseEntity<>(uuid.toString(), headers, HttpStatus.CREATED);
            } else {
                final var newUuid = offeredResourceService.addResource(resourceMetadata);
                headers.setLocation(URI.create(request.getRequestURL().toString()).resolve(newUuid.toString()));
                headers.setContentLength(newUuid.toString().length());
                return new ResponseEntity<>(newUuid.toString(), headers, HttpStatus.CREATED);
            }
        } catch (InvalidResourceException exception) {
            LOGGER.debug("Failed to add resource. The resource is not valid. [exception=({})]",
                exception.getMessage());
            return new ResponseEntity<>("The resource could not be added.",
                headers, HttpStatus.BAD_REQUEST);
        } catch (ResourceAlreadyExistsException exception) {
            LOGGER.debug("Failed to add resource. The resource already exists. [exception=({})]",
                exception.getMessage());
            return new ResponseEntity<>("The resource could not be added. It already exists.",
                headers, HttpStatus.CONFLICT);
        } catch (ResourceException exception) {
            LOGGER.warn("Failed to add resource. Something went wrong. [exception=({})]",
                exception.getMessage());
            return new ResponseEntity<>("The resource could not be added.",
                headers, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Updates resource metadata by id.
     *
     * @param resourceId       The resource id.
     * @param resourceMetadata The updated metadata.
     * @return OK or error response.
     */
    @Operation(summary = "Update Resource", description = "Update the resource's metadata by its uuid.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Ok",
            	content = {@Content(mediaType = MediaType.TEXT_PLAIN_VALUE, 
            		schema = @Schema(title = "The human readable message", type = "string"))}),
            @ApiResponse(responseCode = "400", description = "Invalid resource",
            	content = {@Content(mediaType = MediaType.TEXT_PLAIN_VALUE, 
            		schema = @Schema(title = "The human readable message", type = "string"))}),
            @ApiResponse(responseCode = "404", description = "Not found",
            	content = {@Content(mediaType = MediaType.TEXT_PLAIN_VALUE, 
            		schema = @Schema(title = "The human message", type = "string"))}),
            @ApiResponse(responseCode = "500", description = "Internal server error",
            	content = {@Content(mediaType = MediaType.TEXT_PLAIN_VALUE, 
            		schema = @Schema(title = "The error message", type = "string"))})})
    @RequestMapping(value = "/{resource-id}", method = RequestMethod.PUT,
    		consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public ResponseEntity<String> updateResource(
        @Parameter(description = "The resource uuid.", required = true)
        @PathVariable("resource-id") UUID resourceId,
        @RequestBody ResourceMetadata resourceMetadata) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.setCacheControl(CacheControl.noCache());
       try {
            ((OfferedResourceServiceImpl) offeredResourceService).updateResource(resourceId, resourceMetadata);
            return new ResponseEntity<>("Resource was updated successfully.", headers, HttpStatus.OK);
        } catch (InvalidResourceException exception) {
            LOGGER.debug("Failed to update the resource. The resource is not valid. "
                + "[exception=({})]", exception.getMessage());
            return new ResponseEntity<>("The resource could not be updated.", 
                headers, HttpStatus.BAD_REQUEST);
        } catch (ResourceNotFoundException exception) {
            LOGGER.debug("Failed to update the resource. The resource could not be found."
                + "[exception=({})]", exception.getMessage());
            return new ResponseEntity<>("Resource could not be updated.", 
            		headers, HttpStatus.NOT_FOUND);
        } catch (ResourceException exception) {
            LOGGER.warn("Failed to update the resource. Something went wrong."
                + "[exception=({})]", exception.getMessage());
            return new ResponseEntity<>("Resource could not be updated.",
                headers, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Gets resource metadata by id.
     *
     * @param resourceId The resource id.
     * @return Metadata or an error response.
     */
    @Operation(summary = "Get Resource", description = "Get the resource's metadata by its uuid.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Ok",
            		content = {@Content(mediaType = MediaType.APPLICATION_JSON_VALUE, 
            			schema = @Schema(title = "metadata of the requested resource", implementation = ResourceMetadata.class))}),
            @ApiResponse(responseCode = "404", description = "Not found",
            	content = {@Content(mediaType = MediaType.TEXT_PLAIN_VALUE, 
            		schema = @Schema(title = "The human readable message", type = "string"))}),
            @ApiResponse(responseCode = "500", description = "Internal server error",
            	content = {@Content(mediaType = MediaType.TEXT_PLAIN_VALUE, 
            		schema = @Schema(title = "The error message", type = "string"))})})
    @RequestMapping(value = "/{resource-id}", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<Object> getResource(
        @Parameter(description = "The resource uuid.", required = true)
        @PathVariable("resource-id") UUID resourceId) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noCache());
        try {
            try {
                // Try to find the data in the offeredResourceService
                headers.setContentType(MediaType.APPLICATION_JSON);
                return new ResponseEntity<>(
                    offeredResourceService.getMetadata(resourceId), headers, HttpStatus.OK);
            } catch (ResourceNotFoundException offeredResourceServiceException) {
                LOGGER.debug("Failed to receive the resource from the OfferedResourcesService."
                    + " [exception=({})]", offeredResourceServiceException.getMessage());
                try {
                    // Try to find the data in the requestedResourceService
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    return new ResponseEntity<>(
                        requestedResourceService.getMetadata(resourceId), headers, HttpStatus.OK);
                } catch (ResourceNotFoundException requestedResourceServiceException) {
                    LOGGER
                        .debug("Failed to receive the resource from the RequestedResourcesService."
                            + " [exception=({})]", requestedResourceServiceException.getMessage());
                    // The data could not be found in the offeredResourceService and requestedResourceService
                    LOGGER.debug("Failed to receive the resource. The resource does not exist.");
                    headers.setContentType(MediaType.TEXT_PLAIN);
                    return new ResponseEntity<>("Resource not found.", headers, HttpStatus.NOT_FOUND);
                }
            }
        } catch (InvalidResourceException exception) {
            // The resource has been found but is in an invalid format.
            LOGGER.debug("Failed to receive the resource. The resource is not valid."
                + " [exception=({})]", exception.getMessage());
            headers.setContentType(MediaType.TEXT_PLAIN);
            return new ResponseEntity<>(
                "The resource could not be received. Not a valid resource format.",
                headers, HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (ResourceException exception) {
            LOGGER.warn("Failed to receive the resource. Something went wrong. "
                + "[exception=({})]", exception.getMessage());
            headers.setContentType(MediaType.TEXT_PLAIN);
            return new ResponseEntity<>("Resource could not be received.",
            		headers, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Deletes resource by id.
     *
     * @param resourceId The resource id.
     * @return OK or error response.
     */
    @Operation(summary = "Delete Resource", description = "Delete a resource by its uuid.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Ok",
            	content = {@Content(mediaType = MediaType.TEXT_PLAIN_VALUE, 
            		schema = @Schema(title = "The success message", type = "string"))}),
            @ApiResponse(responseCode = "404", description = "Not found",
            	content = {@Content(mediaType = MediaType.TEXT_PLAIN_VALUE, 
            		schema = @Schema(title = "The error message", type = "string"))})})
    @RequestMapping(value = "/{resource-id}", method = RequestMethod.DELETE)
    @ResponseBody
    public ResponseEntity<String> deleteResource(
        @Parameter(description = "The resource uuid.", required = true)
        @PathVariable("resource-id") UUID resourceId) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.setCacheControl(CacheControl.noCache());
        if (offeredResourceService.deleteResource(resourceId)) {
            return new ResponseEntity<>("Resource was deleted successfully.",
                headers, HttpStatus.OK);
        } else {
            LOGGER.debug("Failed to delete the resource from the OfferedResourcesService.");
            if (requestedResourceService.deleteResource(resourceId)) {
                return new ResponseEntity<>("Resource was deleted successfully.", HttpStatus.OK);
            } else {
                LOGGER.debug("Failed to delete the resource from the RequestedResourcesService.");
                LOGGER.debug("Failed to delete the resource. The resource does not exist.");
                return new ResponseEntity<>("The resource could not be found.",
                    headers, HttpStatus.NOT_FOUND);
            }
        }
    }

    /**
     * Updates usage policy.
     *
     * @param resourceId The resource id.
     * @param policy     The resource's usage policy as string.
     * @return OK or an error response.
     */
    @Operation(summary = "Update Resource Contract", description = "Update the resource's usage policy.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Ok",
            		content = {@Content(mediaType = MediaType.TEXT_XML_VALUE, 
            		schema = @Schema(title = "The success message", type = "string"))}),
            @ApiResponse(responseCode = "400", description = "Invalid resource",
            		content = {@Content(mediaType = MediaType.TEXT_XML_VALUE, 
            		schema = @Schema(title = "The error message", type = "string"))}),
            @ApiResponse(responseCode = "404", description = "Not found",
            		content = {@Content(mediaType = MediaType.TEXT_XML_VALUE, 
            		schema = @Schema(title = "The error message", type = "string"))}),
            @ApiResponse(responseCode = "500", description = "Internal server error",
            		content = {@Content(mediaType = MediaType.TEXT_XML_VALUE, 
            		schema = @Schema(title = "The error message", type = "string"))})})
    @RequestMapping(value = "/{resource-id}/contract", method = RequestMethod.PUT, produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public ResponseEntity<String> updateContract(
        @Parameter(description = "The resource uuid.", required = true)
        @PathVariable("resource-id") UUID resourceId,
        @Parameter(description = "A new resource contract.", required = true)
        @RequestBody String policy) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.setCacheControl(CacheControl.noCache());
        try {
            policyHandler.getPattern(policy);
            ((OfferedResourceServiceImpl) offeredResourceService).updateContract(resourceId, policy);
            return new ResponseEntity<>("Contract was updated successfully.", headers, HttpStatus.OK);
        } catch (UnsupportedPatternException | RequestFormatException exception) {
            // The policy is not in the correct format.
            LOGGER.debug("Failed to update the resource contract. The policy is malformed. "
                + "[exception=({})]", exception.getMessage());
            return new ResponseEntity<>("Policy syntax error.", headers, HttpStatus.BAD_REQUEST);
        } catch (ResourceNotFoundException exception) {
            // The resource could not be found.
            LOGGER.debug("Failed to update the resource contract. The resource does not exist. "
                + "[exception=({})]", exception.getMessage());
            return new ResponseEntity<>("The resource could not be found.",
                headers, HttpStatus.NOT_FOUND);
        } catch (InvalidResourceException exception) {
            // The resource has been found but is in an invalid format.
            LOGGER.debug("Failed to update the resource contract. The resource is not valid. "
                + "[exception=({})]", exception.getMessage());
            return new ResponseEntity<>("The resource could not be received. Not a " +
                "valid resource format.", headers, HttpStatus.BAD_REQUEST);
        } catch (ResourceException exception) {
            LOGGER.warn("Failed to update the resource contract. Something went wrong. "
                + "[exception=({})]", exception.getMessage());
            return new ResponseEntity<>("Resource could not be updated.",
                headers, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Gets usage policy.
     *
     * @param resourceId The resource id.
     * @return Contract or an error response.
     */
    @Operation(summary = "Get Resource Contract", description = "Get the resource's usage policy.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Ok",
            		content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            		schema = @Schema(title = "The contract representation", implementation = Contract.class))),
//    		content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
//    		schema = @Schema(title = "The contract representation", type = "string"))),
            @ApiResponse(responseCode = "404", description = "Not found",
            		content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
            		schema = @Schema(title = "The error message", type = "string"))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
            		content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
            		schema = @Schema(title = "The error message", type = "string")))})
    @RequestMapping(value = "/{resource-id}/contract", method = RequestMethod.GET, produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public ResponseEntity<Object> getContract(
        @Parameter(description = "The resource uuid.", required = true)
        @PathVariable("resource-id") UUID resourceId) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noCache());
       try {
            try {
                // Try to find the data in the offeredResourceService
                final var policy = new SerializerProvider().getSerializer().deserialize(offeredResourceService.getMetadata(resourceId).getPolicy(), Contract.class);
                headers.setContentType(MediaType.APPLICATION_JSON);
                return new ResponseEntity<>(policy, headers, HttpStatus.OK);
            } catch (ResourceNotFoundException offeredResourceServiceException) {
                LOGGER.debug("Failed to receive the resource from the OfferedResourcesService."
                    + " [exception=({})]", offeredResourceServiceException.getMessage());
                try {
                    // Try to find the data in the requestedResourceService
                    final var policy = new SerializerProvider().getSerializer().deserialize(requestedResourceService.getMetadata(resourceId).getPolicy(), Contract.class);
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    return new ResponseEntity<>(policy, headers, HttpStatus.OK);
                } catch (ResourceNotFoundException requestedResourceServiceException) {
                    // The data could not be found in the offeredResourceService and requestedResourceService
                    LOGGER
                        .debug("Failed to receive the resource from the RequestedResourcesService."
                            + "exception=({})]", requestedResourceServiceException.getMessage());
                    LOGGER.debug(
                        "Failed to receive the resource contract. The resource does not exist.");
                    headers.setContentType(MediaType.TEXT_PLAIN);
                    return new ResponseEntity<>("Resource not found.", headers, HttpStatus.NOT_FOUND);
                }
            }
        } catch (ResourceNotFoundException exception) {
            // The resource could not be found.
            LOGGER.debug("Failed to receive the resource contract. The resource does not exist."
                + "exception=({})]", exception.getMessage());
            headers.setContentType(MediaType.TEXT_PLAIN);
            return new ResponseEntity<>("The resource could not be found.", headers, HttpStatus.NOT_FOUND);
        } catch (InvalidResourceException exception) {
            // The resource has been found but is in an invalid format.
            LOGGER.debug("Failed to receive the resource contract. The resource is not valid. "
                + "[exception=({})]", exception.getMessage());
            headers.setContentType(MediaType.TEXT_PLAIN);
            return new ResponseEntity<>("The resource could not be received. Not a " +
                "valid resource format.", headers, HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (Throwable exception) {
            LOGGER.warn("Failed to receive the resource contract. Something went wrong. "
                + "[exception=({})]", exception.getMessage());
            headers.setContentType(MediaType.TEXT_PLAIN);
            return new ResponseEntity<>("Contract could not be received.",
                headers, HttpStatus.INTERNAL_SERVER_ERROR);
        } 
    }

    /**
     * Get how often resource data has been accessed.
     *
     * @param resourceId a {@link java.util.UUID} object.
     * @return a {@link org.springframework.http.ResponseEntity} object.
     */
    @Operation(summary = "Get Data Access", description = "Get the number of the resource's data access.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Ok"),
            @ApiResponse(responseCode = "404", description = "Not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")})
    @RequestMapping(value = "/{resource-id}/access", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<Object> getAccess(
        @Parameter(description = "The resource uuid.", required = true)
        @PathVariable("resource-id") UUID resourceId) {
        try {
            final var resource = ((RequestedResourceServiceImpl) requestedResourceService).getResource(resourceId);
            if (resource == null) {
                LOGGER
                    .debug("Failed to received the resource access. The resource does not exist.");
                return new ResponseEntity<>("Resource not found.", HttpStatus.NOT_FOUND);
            }

            return new ResponseEntity<>(resource.getAccessed(), HttpStatus.OK);
        } catch (InvalidResourceException exception) {
            // The resource has been found but is in an invalid format.
            LOGGER.debug("Failed to receive the resource access. The resource is not valid. "
                + "[exception=({})]", exception.getMessage());
            return new ResponseEntity<>("The resource could not be received. Not a " +
                "valid resource format.", HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (ResourceException exception) {
            LOGGER.warn("Failed to receive the resource access. Something went wrong."
                + " [exception=({})]", exception.getMessage());
            return new ResponseEntity<>("Access counter could not be received.",
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Adds resource representation.
     *
     * @param resourceId     The resource id.
     * @param representation A new representation.
     * @return OK or an error response.
     */
    @Operation(summary = "Add Representation", description = "Add a representation to a resource.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Representation created", 
            		content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE, 
            		schema = @Schema(title = "the UUID of the representation", type = "string"),
            		examples = {@ExampleObject(value = "6f182991-0d99-4bed-a03e-31ea8b70afad")})),
            @ApiResponse(responseCode = "400", description = "Invalid representation",
            		content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
            		schema = @Schema(title = "The error description", type = "string"))),
            @ApiResponse(responseCode = "404", description = "Not found",
            		content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
            		schema = @Schema(title = "the not found error message", type = "string"))),
            @ApiResponse(responseCode = "409", description = "Representation already exists",
            		content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
            		schema = @Schema(title = "the 409 error message", type = "string"))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
            		content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
            		schema = @Schema(title = "the error message", type = "string")))})
    @RequestMapping(value = "/{resource-id}/representation", method = RequestMethod.POST,
    	consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public ResponseEntity<String> addRepresentation(
        @Parameter(description = "The resource uuid.", required = true)
        @PathVariable("resource-id") UUID resourceId,
        @Parameter(description = "A new resource representation.", required = true)
        @RequestBody ResourceRepresentation representation,
        @RequestParam(value = "id", required = false) UUID uuid) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.setCacheControl(CacheControl.noCache());
        try {
            UUID newUuid;
            if (uuid != null) {
                newUuid = ((OfferedResourceServiceImpl) offeredResourceService)
                    .addRepresentationWithId(resourceId, representation, uuid);
            } else {
                newUuid = ((OfferedResourceServiceImpl) offeredResourceService)
                    .addRepresentation(resourceId, representation);
            }

            headers.setLocation(URI.create(request.getRequestURL().toString()).resolve(newUuid.toString()));
            headers.setContentLength(newUuid.toString().length());
            return new ResponseEntity<>(newUuid.toString(), headers, HttpStatus.CREATED);
        } catch (ResourceAlreadyExistsException exception) {
            LOGGER.debug("Failed to add resource representation. The representation already exists."
                + "[exception=({})]", exception.getMessage());
            return new ResponseEntity<>("The representation could not be added. It already exits.",
                headers, HttpStatus.CONFLICT);
        } catch (ResourceNotFoundException exception) {
            // The resource could not be found.
            LOGGER.debug(
                "Failed to add resource representation. The resource does not exist. [exception=({})]",
                exception.getMessage());
            return new ResponseEntity<>("The representation could not be found.",
                headers, HttpStatus.NOT_FOUND);
        } catch (InvalidResourceException exception) {
            // The resource has been found but is in an invalid format.
            LOGGER.debug(
                "Failed to add resource representation. The resource is not valid. [exception=({})]",
                exception.getMessage());
            return new ResponseEntity<>("The resource could not be received. Not a " +
                "valid resource format.", headers, HttpStatus.BAD_REQUEST);
        } catch (ResourceException exception) {
            LOGGER.warn(
                "Failed to add resource representation. Something went wrong. [exception=({})]",
                exception.getMessage());
            return new ResponseEntity<>("The representation could not be added.",
                headers, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Updates resource representation.
     *
     * @param resourceId       The resource id.
     * @param representationId The representation id.
     * @param representation   A new representation.
     * @return OK or an error response.
     */
    @Operation(summary = "Update representation",
        description = "Update a resource's representation by its uuid.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Ok", 
            		content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
            		schema = @Schema(title = "the success message", type = "string"))),
            @ApiResponse(responseCode = "400", description = "Invalid representation",
		    		content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
		    		schema = @Schema(title = "The error description", type = "string"))),
		    @ApiResponse(responseCode = "404", description = "Not found",
		    		content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
		    		schema = @Schema(title = "the not found error message", type = "string"))),
		    @ApiResponse(responseCode = "500", description = "Internal server error",
		    		content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
		    		schema = @Schema(title = "the error message", type = "string")))})
    @RequestMapping(value = "/{resource-id}/{representation-id}", method = RequestMethod.PUT, 
    	produces = MediaType.TEXT_PLAIN_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> updateRepresentation(
        @Parameter(description = "The resource uuid.", required = true)
        @PathVariable("resource-id") UUID resourceId,
        @Parameter(description = "The representation uuid.", required = true)
        @PathVariable("representation-id") UUID representationId,
        @Parameter(description = "A new resource representation.", required = true)
        @RequestBody ResourceRepresentation representation) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.setCacheControl(CacheControl.noCache());
        try {
            ((OfferedResourceServiceImpl) offeredResourceService)
                .updateRepresentation(resourceId, representationId, representation);
            return new ResponseEntity<>("Representation was updated successfully.", headers, HttpStatus.OK);
        } catch (ResourceNotFoundException exception) {
            LOGGER
                .debug("Failed to update the resource representation. The resource does not exist."
                    + " [exception=({})]", exception.getMessage());
            return new ResponseEntity<>("The representation could not be found.",
                headers, HttpStatus.NOT_FOUND);
        } catch (InvalidResourceException exception) {
            // The resource has been found but is in an invalid format.
            LOGGER.debug("Failed to update the resource representation. The resource is not valid."
                + " [exception=({})]", exception.getMessage());
            return new ResponseEntity<>("The resource could not be received. Not a " +
                "valid resource format.", headers, HttpStatus.BAD_REQUEST);
        } catch (ResourceException exception) {
            LOGGER.warn("Failed to update the resource representation. Something went wrong."
                + " [exception=({})]", exception.getMessage());
            return new ResponseEntity<>("The representation could not be updated.",
                headers, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Adds resource representation.
     *
     * @param resourceId       The resource id.
     * @param representationId The representation id.
     * @return OK or an error response.
     */
    @Operation(summary = "Get Resource Representation", description = "Get the resource's representation by its uuid.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Ok",
            		content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, 
            		schema = @Schema(title = "the requested resource representation", implementation = ResourceRepresentation.class))),
            @ApiResponse(responseCode = "404", description = "Not found",
            		content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,  
            		schema = @Schema(title = "The error message", type = "string"))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
            		content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
            		schema = @Schema(title = "The error message", type = "string")))})
    @RequestMapping(value = "/{resource-id}/{representation-id}", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<Object> getRepresentation(
        @Parameter(description = "The resource uuid.", required = true)
        @PathVariable("resource-id") UUID resourceId,
        @Parameter(description = "The representation uuid.", required = true)
        @PathVariable("representation-id") UUID representationId) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noCache());
        try {
            final var representation =
                offeredResourceService.getRepresentation(resourceId, representationId);
            headers.setContentType(MediaType.APPLICATION_JSON);
            return new ResponseEntity<>(representation, headers, HttpStatus.OK);
        } catch (ResourceNotFoundException exception) {
            // The resource could not be found.
            LOGGER.debug(
                "Failed to received the resource representation. The resource does not exist. "
                    + "[exception=({})]", exception.getMessage());
            headers.setContentType(MediaType.TEXT_PLAIN);
            return new ResponseEntity<>("The representation could not be found.",
                headers, HttpStatus.NOT_FOUND);
        } catch (InvalidResourceException exception) {
            // The resource has been found but is in an invalid format.
            LOGGER
                .debug("Failed to received the resource representation. The resource is not valid. "
                    + "[exception=({})]", exception.getMessage());
            headers.setContentType(MediaType.TEXT_PLAIN);
            return new ResponseEntity<>("The resource could not be received. Not a " +
                "valid resource format.", headers, HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (ResourceException exception) {
            LOGGER.warn("Failed to received the resource representation. Something went wrong. "
                + "[exception=({})]", exception.getMessage());
            headers.setContentType(MediaType.TEXT_PLAIN);
            return new ResponseEntity<>("The representation could not be received.",
                headers, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Removes resource representation.
     *
     * @param resourceId       The resource id.
     * @param representationId The representation id.
     * @return OK or an error response.
     */
    @Operation(summary = "Remove Resource Representation",
        description = "Remove a resource's representation by its uuid.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Ok"),
            @ApiResponse(responseCode = "404", description = "Not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")})
    @RequestMapping(value = "/{resource-id}/{representation-id}", method = RequestMethod.DELETE)
    @ResponseBody
    public ResponseEntity<String> deleteRepresentation(
        @Parameter(description = "The resource uuid.", required = true)
        @PathVariable("resource-id") UUID resourceId,
        @Parameter(description = "The representation uuid.", required = true)
        @PathVariable("representation-id") UUID representationId) {
        try {
            if (((OfferedResourceServiceImpl) offeredResourceService).deleteRepresentation(resourceId, representationId)) {
                return new ResponseEntity<>("Representation was deleted successfully.",
                    HttpStatus.OK);
            } else {
                return new ResponseEntity<>("The representation could not be found.",
                    HttpStatus.NOT_FOUND);
            }
        } catch (ResourceNotFoundException exception) {
            // The resource could not be found.
            LOGGER.debug(
                "Failed to delete the resource representation. The resource could not be found."
                    + " [exception=({})]", exception.getMessage());
            return new ResponseEntity<>("The representation could not be found.",
                HttpStatus.NOT_FOUND);
        } catch (InvalidResourceException exception) {
            // The resource has been found but is in an invalid format.
            LOGGER.debug("Failed to delete the resource representation. The resource is not valid."
                + " [exception=({})]", exception.getMessage());
            return new ResponseEntity<>("The resource could not be received. Not a " +
                "valid resource format.", HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (ResourceException exception) {
            LOGGER.warn("Failed to delete the resource representation. Something went wrong."
                + " [exception=({})]", exception.getMessage());
            return new ResponseEntity<>("The representation could not be received.",
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
