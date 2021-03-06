package eu.openminted.content.service.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.openminted.content.connector.Query;
import eu.openminted.content.connector.SearchResult;
import eu.openminted.content.service.ContentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.jws.WebParam;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by antleb on 11/16/16.
 */
@RestController
public class ContentServiceController {

    @Autowired
    private ContentService contentService;

    @RequestMapping(value="/content/browse/", method = RequestMethod.GET, headers = "Accept=application/json")
    public SearchResult browse(@RequestParam(value = "facets") String facets) {

        List<String> facetList = Arrays.asList(facets.split(","));

        try {
            Map<String, List<String>> params = new HashMap<>();

            params.put("language", Arrays.asList("english", "somalian"));
            params.put("year", Arrays.asList("2012"));

            Query q = new Query("keyword", params, Arrays.asList("language", "year"), 0, 0);

            System.out.println(new ObjectMapper().writeValueAsString(q));

        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }

        return contentService.search(new Query(null, new HashMap<>(), facetList, 0, 0));
    }

    @RequestMapping(value="/content/browse/", method = RequestMethod.POST, headers = "Accept=application/json")
    public SearchResult browse(@RequestBody Query query) {

        return contentService.search(query);
    }
}