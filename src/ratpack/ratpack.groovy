import com.zaxxer.hikari.HikariConfig
import org.pac4j.http.client.FormClient
import org.pac4j.http.credentials.SimpleTestUsernamePasswordAuthenticator
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ratpack.codahale.metrics.CodaHaleMetricsModule
import ratpack.codahale.metrics.HealthCheckHandler
import ratpack.codahale.metrics.MetricsWebsocketBroadcastHandler
import ratpack.config.ConfigurationData
import ratpack.config.Configurations
import ratpack.error.ServerErrorHandler
import ratpack.example.books.*
import ratpack.form.Form
import ratpack.groovy.sql.SqlModule
import ratpack.groovy.template.MarkupTemplateModule
import ratpack.hikari.HikariModule
import ratpack.hystrix.HystrixMetricsEventStreamHandler
import ratpack.hystrix.HystrixModule
import ratpack.jackson.JacksonModule
import ratpack.pac4j.Pac4jModule
import ratpack.remote.RemoteControlModule
import ratpack.rx.RxRatpack
import ratpack.server.ReloadInformant
import ratpack.server.ServerLifecycleListener
import ratpack.server.StartEvent
import ratpack.session.SessionModule
import ratpack.session.store.MapSessionsModule

import static ratpack.groovy.Groovy.groovyMarkupTemplate
import static ratpack.groovy.Groovy.ratpack

final Logger log = LoggerFactory.getLogger(ratpack.class);

ratpack {
    bindings {
        ConfigurationData configData = Configurations.config()
                .props("$serverConfig.baseDir.file/application.properties")
                .env()
                .sysProps()
                .build()
        bindInstance(IsbndbConfig, configData.get("/isbndb", IsbndbConfig))
        bindInstance(ReloadInformant, configData.reloadInformant)

        bind DatabaseHealthCheck
        add new CodaHaleMetricsModule(), { it.enable(true).jvmMetrics(true).jmx { it.enable(true) }.healthChecks(true) }
        add(HikariModule) { HikariConfig c ->
            c.addDataSourceProperty("URL", "jdbc:h2:mem:dev;INIT=CREATE SCHEMA IF NOT EXISTS DEV")
            c.setDataSourceClassName("org.h2.jdbcx.JdbcDataSource")
        }
        add new SqlModule()
        add new JacksonModule()
        add new BookModule()
        add new RemoteControlModule(), configData.get("/remote", RemoteControlModule.Config), { }
        add new SessionModule()
        add new MapSessionsModule(10, 5)
        add new Pac4jModule<>(new FormClient("/login", new SimpleTestUsernamePasswordAuthenticator()), new AuthPathAuthorizer())
        add new MarkupTemplateModule()
        add new HystrixModule().sse()
        bind MarkupTemplateRenderableDecorator

        bindInstance ServerLifecycleListener, new ServerLifecycleListener()  {
            void onStart(StartEvent event) throws Exception {
                log.info "Initializing RX"
                RxRatpack.initialize()
                event.registry.get(BookService).createTable()
            }
        }

        bind ServerErrorHandler, ErrorHandler
    }

    handlers { BookService bookService ->
        get {
            bookService.all().toList().subscribe { List<Book> books ->
                def isbndbApikey = context.get(IsbndbConfig).apikey

                render groovyMarkupTemplate("listing.gtpl",
                        isbndbApikey: isbndbApikey,
                        title: "Books",
                        books: books,
                        msg: request.queryParams.msg ?: "")
            }
        }

        handler("create") {
            byMethod {
                get {
                    render groovyMarkupTemplate("create.gtpl",
                            title: "Create Book",
                            isbn: '',
                            quantity: '',
                            price: '',
                            method: 'post',
                            action: '',
                            buttonText: 'Create'
                    )
                }
                post {
                    Form form = parse(Form)
                    bookService.insert(
                            form.isbn,
                            form.get("quantity").asType(Long),
                            form.get("price").asType(BigDecimal)
                    ).single().subscribe() { String isbn ->
                        redirect "/?msg=Book+$isbn+created"
                    }
                }
            }
        }

        handler("update/:isbn") {
            def isbn = pathTokens["isbn"]

            bookService.find(isbn).single().subscribe { Book book ->
                if (book == null) {
                    clientError(404)
                } else {
                    byMethod {
                        get {
                            render groovyMarkupTemplate("update.gtpl",
                                    title: "Update Book",
                                    method: 'post',
                                    action: '',
                                    buttonText: 'Update',
                                    isbn: book.isbn,
                                    bookTitle: book.title,
                                    author: book.author,
                                    publisher: book.publisher,
                                    quantity: book.quantity,
                                    price: book.price)
                        }
                        post {
                            Form form = parse(Form)
                            bookService.update(
                                    isbn,
                                    form.get("quantity").asType(Long),
                                    form.get("price").asType(BigDecimal)
                            ) subscribe {
                                redirect "/?msg=Book+$isbn+updated"
                            }
                        }
                    }
                }
            }
        }

        post("delete/:isbn") {
            def isbn = pathTokens["isbn"]
            bookService.delete(isbn).subscribe {
                redirect "/?msg=Book+$isbn+deleted"
            }
        }

        prefix("api/book") {
            handler chain(registry.get(BookRestEndpoint))
        }

        prefix("admin") {
            get("health-check/:name?", new HealthCheckHandler())
            get("metrics-report", new MetricsWebsocketBroadcastHandler())

            get("metrics") {
                render groovyMarkupTemplate("metrics.gtpl", title: "Metrics")
            }
        }
        get("hystrix.stream", new HystrixMetricsEventStreamHandler())

        handler("login") {
            render groovyMarkupTemplate("login.gtpl",
                    title: "Login",
                    action: '/pac4j-callback',
                    method: 'get',
                    buttonText: 'Login',
                    error: request.queryParams.error ?: "")
        }

        assets "public"
    }

}
