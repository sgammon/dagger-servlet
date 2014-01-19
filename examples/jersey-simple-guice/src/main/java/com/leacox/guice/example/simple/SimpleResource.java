package com.leacox.guice.example.simple;

import com.google.inject.servlet.RequestScoped;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

/**
 * @author John Leacox
 */
@Path("/")
@RequestScoped
public class SimpleResource {
    private final SimpleService simpleService;

    @Inject
    SimpleResource(SimpleService simpleService) {
        this.simpleService = simpleService;
    }

    @Path("/display")
    @GET
    public Response getDisplay() {
        return Response.ok(simpleService.getDisplay()).build();
    }
}
