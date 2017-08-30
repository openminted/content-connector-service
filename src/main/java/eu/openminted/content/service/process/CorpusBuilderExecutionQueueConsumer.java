package eu.openminted.content.service.process;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.openminted.content.connector.ContentConnector;
import eu.openminted.content.connector.Query;
import eu.openminted.content.service.cache.CacheClient;
import eu.openminted.content.service.database.CorpusBuilderInfoDao;
import eu.openminted.content.service.mail.EmailMessage;
import eu.openminted.content.service.messages.JMSMessage;
import eu.openminted.content.service.messages.JMSProducer;
import eu.openminted.content.service.model.CorpusBuilderInfoModel;
import eu.openminted.corpus.CorpusStatus;
import eu.openminted.registry.domain.Corpus;
import eu.openminted.store.restclient.StoreRESTClient;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

@Component
public class CorpusBuilderExecutionQueueConsumer {
    private static Logger log = Logger.getLogger(CorpusBuilderExecutionQueueConsumer.class.getName());

    private BlockingQueue<Corpus> corpora;

    @Autowired(required = false)
    private List<ContentConnector> contentConnectors;

    @Autowired
    private CorpusBuilderInfoDao corpusBuilderInfoDao;

    @Autowired
    private StoreRESTClient storeRESTClient;

    @Autowired
    private ThreadPoolExecutor threadPoolExecutor;

    @Autowired
    private JMSProducer producer;

    @Autowired
    private CacheClient cacheClient;

    @org.springframework.beans.factory.annotation.Value("${tempDirectoryPath}")
    private String tempDirectoryPath;

    @org.springframework.beans.factory.annotation.Value("${registry.host}")
    private String registryHost;

    @org.springframework.beans.factory.annotation.Value("${content.limit:0}")
    private Integer contentLimit;

    private ThreadPoolExecutor queueTasksThreadPoolExecutor;

    public void init(BlockingQueue<Corpus> corpora) {

        // if registryHost ends with '/' remove it
        registryHost = registryHost.replaceAll("/$", "");

        if (this.corpora == null) this.corpora = corpora;
        if (this.queueTasksThreadPoolExecutor == null)
            this.queueTasksThreadPoolExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);

        while (true) {
            try {
                Corpus corpus = corpora.poll(100, TimeUnit.MILLISECONDS);
                if (corpus != null) {
                    String corpusId = corpus.getMetadataHeaderInfo().getMetadataRecordIdentifier().getValue();

                    this.queueTasksThreadPoolExecutor.execute(() -> {
                        if (contentConnectors != null) {
                            CorpusBuilderInfoModel corpusBuilderInfoModel = null;
                            try {
                                corpusBuilderInfoModel = corpusBuilderInfoDao.find(corpusId);
                                if (corpusBuilderInfoModel.getStatus().equalsIgnoreCase(CorpusStatus.CANCELED.toString())
                                        || corpusBuilderInfoModel.getStatus().equalsIgnoreCase(CorpusStatus.DELETED.toString())
                                        || corpusBuilderInfoModel.getStatus().equalsIgnoreCase(CorpusStatus.PROCESSING.toString()))
                                    return;

                                corpusBuilderInfoModel.setStatus(CorpusStatus.PROCESSING.toString());
                                corpusBuilderInfoDao.update(corpusBuilderInfoModel.getId(), "status", CorpusStatus.PROCESSING);

                                Collection<Future<?>> futures = new ArrayList<>();
                                Query query = new ObjectMapper().readValue(corpusBuilderInfoModel.getQuery(), Query.class);

                                // retrieve connectors from query
                                List<String> connectors = new ArrayList<>();
                                if (query.getParams().containsKey("source")
                                        && query.getParams().get("source") != null
                                        && query.getParams().get("source").size() > 0) {
                                    connectors.addAll(query.getParams().get("source"));
                                }

                                for (ContentConnector connector : contentConnectors) {

                                    if (connectors.size() > 0 && !connectors.contains(connector.getSourceName()))
                                        continue;

                                    FetchMetadataTask task = new FetchMetadataTask(storeRESTClient,
                                            connector,
                                            query,
                                            tempDirectoryPath,
                                            corpusBuilderInfoModel.getArchiveId(),
                                            contentLimit,
                                            producer);
                                    task.setCacheClient(cacheClient);
                                    task.setCorpusBuilderInfoDao(corpusBuilderInfoDao);
                                    task.setCorpusId(corpusId);
                                    futures.add(threadPoolExecutor.submit(task));
                                }

                                for (Future<?> future : futures) {
                                    try {
                                        future.get();
                                    } catch (InterruptedException e) {

                                        log.info("Thread Interrupted or error in execution");
                                        corpusBuilderInfoModel.setStatus(CorpusStatus.CANCELED.toString());
                                        corpusBuilderInfoDao.update(corpusBuilderInfoModel.getId(), "status", CorpusStatus.CANCELED);

                                    } catch (Exception e) {
                                        log.error("CorpusBuilderImpl.buildCorpus - Inner exception at the future.get method", e);
                                    }
                                }
                            } catch (Exception ex) {

                                log.error("CorpusBuilderImpl.buildCorpus", ex);
                            }

                            corpusBuilderInfoModel = corpusBuilderInfoDao.find(corpusId);
                            String text;
                            if (corpusBuilderInfoModel != null
                                    && (!corpusBuilderInfoModel.getStatus().equalsIgnoreCase(CorpusStatus.CANCELED.toString())
                                    || corpusBuilderInfoModel.getStatus().equalsIgnoreCase(CorpusStatus.DELETED.toString()))) {

                                storeRESTClient.finalizeArchive(corpusBuilderInfoModel.getArchiveId());

                                corpusBuilderInfoModel.setStatus(CorpusStatus.CREATED.toString());
                                corpusBuilderInfoDao.update(corpusBuilderInfoModel.getId(), "status", CorpusStatus.CREATED);
                                text = "Corpus building with ID " + corpusId + " has been created at archive with ID " + corpusBuilderInfoModel.getArchiveId();

                                try {
//                                    String url = registryHost + "/omtd-registry/request/corpus";
//                                    RestTemplate restTemplate = new RestTemplate();
//                                    restTemplate.postForObject(url, corpus, Corpus.class);
//                                    new Thread(() -> producer.send("Corpus Build: Sending corpus with ID " + corpus.getMetadataHeaderInfo().getMetadataRecordIdentifier().getValue() + " to registry")).start();

                                    producer.sendMessage("Corpus", corpus);
                                } catch (Exception e) {
                                    log.error("CorpusBuilderImpl.buildCorpus: Error posting corpus at registry. Error stacktrace is omitted on purpose");
                                    String errorMessage = "Corpus Build: Error sending corpus with  ID " + corpus.getMetadataHeaderInfo().getMetadataRecordIdentifier().getValue() + " to registry";
                                    producer.sendMessage("Corpus", errorMessage);
                                }
                            } else {
                                text = "Corpus building with corpusId " + corpusId + " has been interrupted!";
                            }
                            if (!corpus.getCorpusInfo().getContactInfo().getContactEmail().isEmpty()) {
                                EmailMessage emailMessage = new EmailMessage();
                                emailMessage.setRecipient(corpus.getCorpusInfo().getContactInfo().getContactEmail());
                                emailMessage.setSubject("OpenMinTeD Corpus build request");
                                emailMessage.setText(text);

                                producer.sendMessage(EmailMessage.class.toString(), emailMessage);
                            }
                        }
                    });
                }

            } catch (InterruptedException e) {
                log.error(e);
            }
        }
    }
}