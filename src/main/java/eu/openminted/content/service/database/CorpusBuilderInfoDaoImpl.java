package eu.openminted.content.service.database;

import eu.openminted.content.service.model.CorpusBuilderInfoModel;
import eu.openminted.corpus.CorpusStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class CorpusBuilderInfoDaoImpl implements CorpusBuilderInfoDao {

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    public void setNamedParameterJdbcTemplate(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public CorpusBuilderInfoModel find(String id) {

        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        String sql = "SELECT * FROM corpusbuilderinfo WHERE id=:id;";

        return this.jdbcTemplate.queryForObject(sql, params, new BeanPropertyRowMapper<>(CorpusBuilderInfoModel.class));
    }

    @Override
    public List<CorpusBuilderInfoModel> findAll() {
        String sql = "SELECT * FROM corpusbuilderinfo;";
        return this.jdbcTemplate.query(sql, new BeanPropertyRowMapper<>(CorpusBuilderInfoModel.class));
    }

    @Override
    public void insert(String id, String token, String query, CorpusStatus status, String archiveId) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("token", token);
        params.put("query", query);
        params.put("status", status.toString());
        params.put("archiveId", archiveId);

        this.jdbcTemplate.update("insert into corpusbuilderinfo values (:id, :token, :query, :status, :archiveId);", params);
    }

    @Override
    public void updateStatus(String id, CorpusStatus status) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("status", status.toString());
        this.jdbcTemplate.update("update corpusbuilderinfo set status = :status where id = :id;", params);
    }

    @Override
    public void update(String id, String field, Object value) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put(field, value.toString());
        String query = String.format("update corpusbuilderinfo set %s = :%s where id = :id;", field, field);
        this.jdbcTemplate.update(query, params);
    }
}
