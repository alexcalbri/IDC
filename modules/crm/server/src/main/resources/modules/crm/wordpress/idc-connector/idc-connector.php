<?php
/**
 * Plugin Name: IDC Connector
 * Description: Conecta Real Estate 7 con el CRM de IdeasCore: propiedades, formularios, favoritos, busquedas guardadas y ventas.
 * Version: 0.2.0
 * Requires PHP: 8.0
 * Author: IdeasCore
 * Text Domain: idc-connector
 *
 * IDC -> sitio: POST /wp-json/idc/v1/rpc {"action": ..., ...} firmado.
 * Sitio -> IDC: POST {idc}/modules/crm/hooks/{empresa}/wordpress con cada evento, firmado.
 * Firma: X-IDC-Timestamp (segundos) y X-IDC-Signature = hex(HMAC-SHA256(secreto, timestamp + "." + cuerpo)).
 * Se rechazan firmas con mas de 5 minutos de diferencia.
 */

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

const IDC_CONNECTOR_VERSION = '0.2.0';
const IDC_CONNECTOR_OPTION  = 'idc_connector_settings';
const IDC_CONNECTOR_QUEUE   = 'idc_connector_queue';
const IDC_CONNECTOR_CRON    = 'idc_connector_retry';
const IDC_CONNECTOR_SKEW    = 300;

/* ---------------------------------------------------------------------
 * Ajustes
 * ------------------------------------------------------------------- */

function idc_connector_settings(): array {
	$saved = get_option( IDC_CONNECTOR_OPTION, [] );
	return wp_parse_args( is_array( $saved ) ? $saved : [], [
		'idc_url'            => '',
		'company'            => '',
		'secret'             => '',
		'send_views'         => '1',
		'recaptcha_enabled'  => '0',
		'recaptcha_site_key' => '',
		'recaptcha_secret'   => '',
		'recaptcha_score'    => '0.5',
	] );
}

function idc_connector_ready(): bool {
	$settings = idc_connector_settings();
	return $settings['idc_url'] !== '' && $settings['company'] !== '' && strlen( $settings['secret'] ) >= 32;
}

function idc_connector_sign( string $timestamp, string $body, string $secret ): string {
	return hash_hmac( 'sha256', $timestamp . '.' . $body, $secret );
}

add_action( 'admin_menu', static function () {
	add_options_page( 'IDC Connector', 'IDC Connector', 'manage_options', 'idc-connector', 'idc_connector_settings_page' );
} );

add_action( 'admin_init', static function () {
	register_setting( 'idc_connector', IDC_CONNECTOR_OPTION, [
		'type'              => 'array',
		'sanitize_callback' => 'idc_connector_sanitize_settings',
	] );
} );

function idc_connector_sanitize_settings( $input ): array {
	$current = idc_connector_settings();
	$input = is_array( $input ) ? $input : [];
	$secret = trim( (string) ( $input['secret'] ?? '' ) );
	$recaptcha_secret = trim( (string) ( $input['recaptcha_secret'] ?? '' ) );
	$score = (float) ( $input['recaptcha_score'] ?? 0.5 );
	return [
		'idc_url'            => untrailingslashit( esc_url_raw( trim( (string) ( $input['idc_url'] ?? '' ) ), [ 'https', 'http' ] ) ),
		'company'            => preg_replace( '/[^a-z0-9_]/', '', strtolower( (string) ( $input['company'] ?? '' ) ) ),
		// Un campo vacio conserva el secreto guardado.
		'secret'             => $secret !== '' ? preg_replace( '/[^A-Za-z0-9]/', '', $secret ) : $current['secret'],
		'send_views'         => empty( $input['send_views'] ) ? '0' : '1',
		'recaptcha_enabled'  => empty( $input['recaptcha_enabled'] ) ? '0' : '1',
		'recaptcha_site_key' => sanitize_text_field( (string) ( $input['recaptcha_site_key'] ?? '' ) ),
		'recaptcha_secret'   => $recaptcha_secret !== '' ? sanitize_text_field( $recaptcha_secret ) : $current['recaptcha_secret'],
		'recaptcha_score'    => (string) min( 1, max( 0, $score ) ),
	];
}

function idc_connector_settings_page(): void {
	if ( ! current_user_can( 'manage_options' ) ) {
		return;
	}
	$settings = idc_connector_settings();
	$test = null;
	if ( isset( $_POST['idc_connector_test'] ) && check_admin_referer( 'idc_connector_test' ) ) {
		$test = idc_connector_send( [ 'event' => 'ping', 'site' => home_url(), 'version' => IDC_CONNECTOR_VERSION ], true );
	}
	$queue = get_option( IDC_CONNECTOR_QUEUE, [] );
	?>
	<div class="wrap">
		<h1>IDC Connector</h1>
		<p>Conecta este sitio con el CRM de IdeasCore. En el CRM ve a <strong>Configuracion &gt; Integraciones &gt; WordPress</strong>, escribe la direccion de este sitio y copia aqui el secreto que genera.</p>
		<?php if ( $test !== null ) : ?>
			<div class="notice <?php echo is_wp_error( $test ) ? 'notice-error' : 'notice-success'; ?>"><p><?php echo esc_html( is_wp_error( $test ) ? $test->get_error_message() : 'Conexion correcta con el CRM.' ); ?></p></div>
		<?php endif; ?>
		<form method="post" action="options.php">
			<?php settings_fields( 'idc_connector' ); ?>
			<table class="form-table" role="presentation">
				<tr><th scope="row"><label for="idc_url">Direccion de IDC</label></th>
					<td><input type="url" class="regular-text" id="idc_url" name="<?php echo esc_attr( IDC_CONNECTOR_OPTION ); ?>[idc_url]" value="<?php echo esc_attr( $settings['idc_url'] ); ?>" placeholder="https://idc.ejemplo.com"></td></tr>
				<tr><th scope="row"><label for="company">Codigo de la empresa</label></th>
					<td><input type="text" class="regular-text" id="company" name="<?php echo esc_attr( IDC_CONNECTOR_OPTION ); ?>[company]" value="<?php echo esc_attr( $settings['company'] ); ?>"></td></tr>
				<tr><th scope="row"><label for="secret">Secreto</label></th>
					<td><input type="password" class="regular-text" id="secret" name="<?php echo esc_attr( IDC_CONNECTOR_OPTION ); ?>[secret]" value="" placeholder="<?php echo $settings['secret'] !== '' ? 'Guardado (deja vacio para conservarlo)' : ''; ?>" autocomplete="off"></td></tr>
				<tr><th scope="row">Vistas de propiedades</th>
					<td><label><input type="checkbox" name="<?php echo esc_attr( IDC_CONNECTOR_OPTION ); ?>[send_views]" value="1" <?php checked( $settings['send_views'], '1' ); ?>> Enviar al CRM las propiedades que ven los usuarios con sesion</label></td></tr>
				<tr><th scope="row">reCAPTCHA v3 en CT Forms</th>
					<td>
						<label><input type="checkbox" name="<?php echo esc_attr( IDC_CONNECTOR_OPTION ); ?>[recaptcha_enabled]" value="1" <?php checked( $settings['recaptcha_enabled'], '1' ); ?>> Exigir reCAPTCHA al enviar formularios (los usuarios con sesion no lo necesitan)</label><br>
						<input type="text" class="regular-text" name="<?php echo esc_attr( IDC_CONNECTOR_OPTION ); ?>[recaptcha_site_key]" value="<?php echo esc_attr( $settings['recaptcha_site_key'] ); ?>" placeholder="Clave del sitio"><br>
						<input type="password" class="regular-text" name="<?php echo esc_attr( IDC_CONNECTOR_OPTION ); ?>[recaptcha_secret]" value="" placeholder="<?php echo $settings['recaptcha_secret'] !== '' ? 'Clave secreta guardada' : 'Clave secreta'; ?>" autocomplete="off"><br>
						<label>Puntaje minimo <input type="number" step="0.1" min="0" max="1" name="<?php echo esc_attr( IDC_CONNECTOR_OPTION ); ?>[recaptcha_score]" value="<?php echo esc_attr( $settings['recaptcha_score'] ); ?>"></label>
					</td></tr>
			</table>
			<?php submit_button(); ?>
		</form>
		<form method="post">
			<?php wp_nonce_field( 'idc_connector_test' ); ?>
			<p><button type="submit" name="idc_connector_test" class="button">Probar conexion</button>
			<?php if ( is_array( $queue ) && $queue ) : ?> <?php echo (int) count( $queue ); ?> evento(s) pendientes de reenviar.<?php endif; ?></p>
		</form>
	</div>
	<?php
}

/* ---------------------------------------------------------------------
 * IDC -> sitio
 * ------------------------------------------------------------------- */

add_action( 'rest_api_init', static function () {
	register_rest_route( 'idc/v1', '/rpc', [
		'methods'             => 'POST',
		'callback'            => 'idc_connector_rpc',
		'permission_callback' => 'idc_connector_verify_request',
	] );
} );

function idc_connector_verify_request( WP_REST_Request $request ): bool {
	$settings = idc_connector_settings();
	$timestamp = (string) $request->get_header( 'X-IDC-Timestamp' );
	$signature = (string) $request->get_header( 'X-IDC-Signature' );
	if ( strlen( $settings['secret'] ) < 32 || ! ctype_digit( $timestamp ) || abs( time() - (int) $timestamp ) > IDC_CONNECTOR_SKEW ) {
		return false;
	}
	return hash_equals( idc_connector_sign( $timestamp, (string) $request->get_body(), $settings['secret'] ), strtolower( $signature ) );
}

function idc_connector_rpc( WP_REST_Request $request ): WP_REST_Response {
	$data = json_decode( (string) $request->get_body(), true );
	$data = is_array( $data ) ? $data : [];
	switch ( (string) ( $data['action'] ?? '' ) ) {
		case 'ping':
			return new WP_REST_Response( [ 'ok' => true, 'version' => IDC_CONNECTOR_VERSION, 'site' => get_bloginfo( 'name' ), 'theme' => wp_get_theme()->get( 'Name' ) ] );
		case 'search':
			return new WP_REST_Response( idc_connector_search( $data ) );
		case 'listing':
			$post = get_post( absint( $data['id'] ?? 0 ) );
			if ( ! $post || $post->post_type !== 'listings' ) {
				return new WP_REST_Response( [ 'error' => 'Esa propiedad no existe.' ], 404 );
			}
			return new WP_REST_Response( idc_connector_listing( $post, true ) );
		case 'taxonomies':
			return new WP_REST_Response( idc_connector_taxonomies() );
		case 'agents':
			return new WP_REST_Response( idc_connector_agents() );
		case 'sold':
			return idc_connector_mark_sold( $data );
		case 'leads':
			return new WP_REST_Response( idc_connector_leads_pro( $data ) );
		default:
			return new WP_REST_Response( [ 'error' => 'Accion desconocida.' ], 400 );
	}
}

// Taxonomias de Real Estate 7 que sirven como filtros.
function idc_connector_taxonomy_names(): array {
	return [ 'property_type', 'ct_status', 'beds', 'baths', 'city', 'state', 'county', 'zipcode', 'country', 'community', 'additional_features' ];
}

function idc_connector_terms( int $post_id, string $taxonomy ): array {
	$terms = get_the_terms( $post_id, $taxonomy );
	return is_array( $terms ) ? array_values( array_map( static fn( $term ) => html_entity_decode( $term->name ), $terms ) ) : [];
}

function idc_connector_meta( int $post_id, string $key ): string {
	$value = get_post_meta( $post_id, $key, true );
	return is_scalar( $value ) ? trim( (string) $value ) : '';
}

function idc_connector_user( int $user_id ): ?array {
	$user = $user_id ? get_userdata( $user_id ) : false;
	return $user ? [ 'id' => (int) $user->ID, 'name' => $user->display_name, 'email' => $user->user_email ] : null;
}

/** Propiedad con sus campos de Real Estate 7. Con $full agrega galeria, planos, archivos y toda la meta _ct_. */
function idc_connector_listing( WP_Post $post, bool $full = false ): array {
	$id = (int) $post->ID;
	$taxonomies = [];
	foreach ( idc_connector_taxonomy_names() as $taxonomy ) {
		$taxonomies[ $taxonomy ] = idc_connector_terms( $id, $taxonomy );
	}
	$brokerage = absint( idc_connector_meta( $id, '_ct_brokerage' ) );
	$listing = [
		'id'            => $id,
		'title'         => html_entity_decode( get_the_title( $post ) ),
		'url'           => get_permalink( $post ),
		'post_status'   => $post->post_status,
		'price'         => idc_connector_meta( $id, '_ct_price' ),
		'price_prefix'  => idc_connector_meta( $id, '_ct_price_prefix' ),
		'price_postfix' => idc_connector_meta( $id, '_ct_price_postfix' ),
		'mls'           => idc_connector_meta( $id, '_ct_mls' ),
		'sqft'          => idc_connector_meta( $id, '_ct_sqft' ),
		'lot_size'      => idc_connector_meta( $id, '_ct_lotsize' ),
		'year_built'    => idc_connector_meta( $id, '_ct_year_built' ),
		'parking'       => idc_connector_meta( $id, '_ct_parking' ),
		'latlng'        => idc_connector_meta( $id, '_ct_latlng' ),
		'image'         => (string) get_the_post_thumbnail_url( $post, 'large' ),
		'taxonomies'    => $taxonomies,
		'agent'         => idc_connector_user( (int) $post->post_author ),
		'co_agent'      => idc_connector_user( absint( idc_connector_meta( $id, '_ct_co_listing_agent' ) ) ),
		'brokerage'     => $brokerage ? html_entity_decode( get_the_title( $brokerage ) ) : '',
		'sold_date'     => idc_connector_meta( $id, '_ct_sold_date' ),
		'modified'      => get_post_modified_time( 'c', true, $post ),
	];
	if ( ! $full ) {
		return $listing;
	}
	$listing['description'] = wp_strip_all_tags( (string) $post->post_content );
	$listing['open_house'] = [
		'date'  => idc_connector_meta( $id, '_ct_open_house_date' ),
		'start' => idc_connector_meta( $id, '_ct_open_house_start_time' ),
		'end'   => idc_connector_meta( $id, '_ct_open_house_end_time' ),
	];
	$listing['rental'] = [
		'title'        => idc_connector_meta( $id, '_ct_rental_title' ),
		'guests'       => idc_connector_meta( $id, '_ct_rental_guests' ),
		'min_stay'     => idc_connector_meta( $id, '_ct_rental_min_stay' ),
		'checkin'      => idc_connector_meta( $id, '_ct_rental_checkin' ),
		'checkout'     => idc_connector_meta( $id, '_ct_rental_checkout' ),
		'extra_people' => idc_connector_meta( $id, '_ct_rental_extra_people' ),
		'cleaning'     => idc_connector_meta( $id, '_ct_rental_cleaning' ),
		'deposit'      => idc_connector_meta( $id, '_ct_rental_deposit' ),
		'cancellation' => idc_connector_meta( $id, '_ct_rental_cancellation' ),
	];
	$listing['video'] = idc_connector_meta( $id, '_ct_video' );
	$listing['virtual_tour'] = idc_connector_meta( $id, '_ct_virtual_tour_url' );
	$gallery = [];
	foreach ( get_attached_media( 'image', $id ) as $attachment ) {
		$gallery[] = (string) wp_get_attachment_image_url( $attachment->ID, 'large' );
	}
	$listing['gallery'] = array_values( array_filter( $gallery ) );
	// Toda la meta de Real Estate 7 (planos, archivos, IDX...) para no perder ningun campo del tema.
	$meta = [];
	foreach ( get_post_meta( $id ) as $key => $values ) {
		if ( str_starts_with( (string) $key, '_ct_' ) && isset( $values[0] ) ) {
			$value = maybe_unserialize( $values[0] );
			$meta[ $key ] = is_scalar( $value ) ? (string) $value : $value;
		}
	}
	$listing['meta'] = $meta;
	return $listing;
}

function idc_connector_search( array $data ): array {
	$per_page = min( 50, max( 1, absint( $data['per_page'] ?? 20 ) ) );
	$args = [
		'post_type'      => 'listings',
		'post_status'    => 'publish',
		'posts_per_page' => $per_page,
		'paged'          => max( 1, absint( $data['page'] ?? 1 ) ),
		'orderby'        => 'date',
		'order'          => 'DESC',
	];
	$search = sanitize_text_field( (string) ( $data['search'] ?? '' ) );
	$meta_query = [];
	if ( $search !== '' ) {
		// Busca en el titulo (la direccion) y en el MLS.
		if ( preg_match( '/^[A-Za-z0-9-]{3,}$/', $search ) ) {
			$by_mls = get_posts( [ 'post_type' => 'listings', 'post_status' => 'publish', 'fields' => 'ids', 'posts_per_page' => 20, 'meta_key' => '_ct_mls', 'meta_value' => $search ] );
		}
		if ( ! empty( $by_mls ) ) {
			$args['post__in'] = $by_mls;
		} else {
			$args['s'] = $search;
		}
	}
	$tax_query = [];
	foreach ( idc_connector_taxonomy_names() as $taxonomy ) {
		$value = sanitize_text_field( (string) ( $data['filters'][ $taxonomy ] ?? '' ) );
		if ( $value !== '' ) {
			$tax_query[] = [ 'taxonomy' => $taxonomy, 'field' => 'name', 'terms' => $value ];
		}
	}
	if ( $tax_query ) {
		$args['tax_query'] = $tax_query;
	}
	foreach ( [ 'min_price' => '>=', 'max_price' => '<=' ] as $key => $compare ) {
		$value = preg_replace( '/[^0-9.]/', '', (string) ( $data[ $key ] ?? '' ) );
		if ( $value !== '' ) {
			$meta_query[] = [ 'key' => '_ct_price', 'value' => (float) $value, 'compare' => $compare, 'type' => 'NUMERIC' ];
		}
	}
	if ( $meta_query ) {
		$args['meta_query'] = $meta_query;
	}
	$query = new WP_Query( $args );
	return [
		'total'    => (int) $query->found_posts,
		'page'     => (int) $args['paged'],
		'per_page' => $per_page,
		'items'    => array_map( static fn( $post ) => idc_connector_listing( $post ), $query->posts ),
	];
}

function idc_connector_taxonomies(): array {
	$result = [];
	foreach ( idc_connector_taxonomy_names() as $taxonomy ) {
		if ( ! taxonomy_exists( $taxonomy ) ) {
			continue;
		}
		$terms = get_terms( [ 'taxonomy' => $taxonomy, 'hide_empty' => true ] );
		$result[ $taxonomy ] = is_array( $terms ) ? array_values( array_map( static fn( $term ) => [ 'name' => html_entity_decode( $term->name ), 'count' => (int) $term->count ], $terms ) ) : [];
	}
	return $result;
}

// Agentes y brokers: autores de propiedades y usuarios con roles del tema.
function idc_connector_agents(): array {
	$users = get_users( [ 'role__in' => [ 'agent', 'broker', 'administrator', 'editor', 'author', 'contributor' ], 'number' => 500 ] );
	$agents = [];
	foreach ( $users as $user ) {
		$agents[] = [
			'id'       => (int) $user->ID,
			'name'     => $user->display_name,
			'email'    => $user->user_email,
			'roles'    => array_values( $user->roles ),
			'listings' => (int) count_user_posts( $user->ID, 'listings', true ),
		];
	}
	return $agents;
}

/** Marca la propiedad como vendida: estado del tema "Sold", fecha y precio de venta. */
function idc_connector_mark_sold( array $data ): WP_REST_Response {
	$post = get_post( absint( $data['id'] ?? 0 ) );
	if ( ! $post || $post->post_type !== 'listings' ) {
		return new WP_REST_Response( [ 'error' => 'Esa propiedad no existe.' ], 404 );
	}
	$term = get_term_by( 'slug', 'sold', 'ct_status' ) ?: get_term_by( 'name', 'Sold', 'ct_status' ) ?: get_term_by( 'name', 'Vendido', 'ct_status' );
	if ( ! $term ) {
		$created = wp_insert_term( 'Sold', 'ct_status', [ 'slug' => 'sold' ] );
		if ( is_wp_error( $created ) ) {
			return new WP_REST_Response( [ 'error' => 'No se pudo crear el estado Sold.' ], 500 );
		}
		$term = get_term( $created['term_id'], 'ct_status' );
	}
	wp_set_object_terms( $post->ID, [ (int) $term->term_id ], 'ct_status', false );
	$date = preg_match( '/^\d{4}-\d{2}-\d{2}$/', (string) ( $data['date'] ?? '' ) ) ? (string) $data['date'] : gmdate( 'Y-m-d' );
	update_post_meta( $post->ID, '_ct_sold_date', $date );
	$price = preg_replace( '/[^0-9.]/', '', (string) ( $data['price'] ?? '' ) );
	if ( $price !== '' ) {
		update_post_meta( $post->ID, '_idc_sold_price', $price );
	}
	return new WP_REST_Response( idc_connector_listing( $post ) );
}

/* ---------------------------------------------------------------------
 * Sitio -> IDC
 * ------------------------------------------------------------------- */

/**
 * Envia un evento al CRM. Los fallidos quedan en cola (200 como maximo) y se reintentan cada hora.
 *
 * @return true|WP_Error
 */
function idc_connector_send( array $event, bool $blocking = true ) {
	if ( ! idc_connector_ready() ) {
		return new WP_Error( 'idc_connector', 'Configura la direccion de IDC, la empresa y el secreto.' );
	}
	$settings = idc_connector_settings();
	$event['sent_at'] = gmdate( 'c' );
	$body = wp_json_encode( $event, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES );
	$timestamp = (string) time();
	$response = wp_remote_post( $settings['idc_url'] . '/modules/crm/hooks/' . rawurlencode( $settings['company'] ) . '/wordpress', [
		'timeout'     => $blocking ? 8 : 2,
		'blocking'    => $blocking,
		'redirection' => 0,
		'headers'     => [
			'Content-Type'    => 'application/json',
			'X-IDC-Timestamp' => $timestamp,
			'X-IDC-Signature' => idc_connector_sign( $timestamp, $body, $settings['secret'] ),
		],
		'body'        => $body,
	] );
	if ( ! $blocking ) {
		return true;
	}
	$status = is_wp_error( $response ) ? 0 : (int) wp_remote_retrieve_response_code( $response );
	if ( $status >= 200 && $status < 300 ) {
		return true;
	}
	if ( ( $event['event'] ?? '' ) !== 'ping' ) {
		$queue = get_option( IDC_CONNECTOR_QUEUE, [] );
		$queue = is_array( $queue ) ? $queue : [];
		$queue[] = $event;
		update_option( IDC_CONNECTOR_QUEUE, array_slice( $queue, -200 ), false );
	}
	return new WP_Error( 'idc_connector', $status === 0 ? 'No se pudo contactar IDC.' : ( $status === 401 || $status === 403 ? 'IDC rechazo la firma: revisa el secreto y el codigo de la empresa.' : 'IDC respondio HTTP ' . $status . '.' ) );
}


register_activation_hook( __FILE__, static function () {
	if ( ! wp_next_scheduled( IDC_CONNECTOR_CRON ) ) {
		wp_schedule_event( time() + 300, 'hourly', IDC_CONNECTOR_CRON );
	}
} );

register_deactivation_hook( __FILE__, static function () {
	wp_clear_scheduled_hook( IDC_CONNECTOR_CRON );
} );

add_action( IDC_CONNECTOR_CRON, static function () {
	$queue = get_option( IDC_CONNECTOR_QUEUE, [] );
	if ( ! is_array( $queue ) || ! $queue ) {
		return;
	}
	update_option( IDC_CONNECTOR_QUEUE, [], false );
	foreach ( $queue as $event ) {
		if ( is_array( $event ) ) {
			idc_connector_send( $event ); // Si vuelve a fallar se encola otra vez.
		}
	}
} );

// Propiedad publicada a la que pertenece una URL de este sitio, o null.
function idc_connector_listing_from_url( string $url ): ?array {
	if ( $url === '' || wp_parse_url( $url, PHP_URL_HOST ) !== wp_parse_url( home_url(), PHP_URL_HOST ) ) {
		return null;
	}
	$post = get_post( url_to_postid( $url ) );
	return $post && $post->post_type === 'listings' && $post->post_status === 'publish' ? idc_connector_listing( $post ) : null;
}

// Texto de los campos de un formulario, sin los tecnicos.
function idc_connector_fields_text( array $fields ): string {
	$lines = [];
	foreach ( $fields as $label => $value ) {
		if ( is_array( $value ) ) {
			$value = implode( ', ', array_map( 'strval', array_filter( $value, 'is_scalar' ) ) );
		}
		$value = trim( wp_strip_all_tags( (string) $value ) );
		if ( $value === '' || preg_match( '/(nonce|recaptcha|token|_wp_http_referer|action)$/i', (string) $label ) ) {
			continue;
		}
		$lines[] = ucfirst( str_replace( [ 'ct_field_', '_' ], [ '', ' ' ], (string) $label ) ) . ': ' . $value;
	}
	return implode( "\n", $lines );
}

/** CT Forms (Real Estate Core): cada envio va al CRM con la propiedad de la pagina cuando la hay. */
add_action( 'ct_forms_after_send', static function ( $form_id, $fields, $sanitized, $settings, $mail_sent, $name = '', $email = '', $phone = '', $field_data = [], $submission_id = 0 ) {
	$sanitized = is_array( $sanitized ) ? $sanitized : [];
	$listing = null;
	foreach ( $sanitized as $value ) {
		if ( is_string( $value ) && str_starts_with( $value, 'http' ) && ( $listing = idc_connector_listing_from_url( $value ) ) ) {
			break;
		}
	}
	$listing = $listing ?: idc_connector_listing_from_url( (string) wp_get_referer() );
	idc_connector_send( [
		'event'      => 'form',
		'id'         => 'form-' . ( $submission_id ? (int) $submission_id : md5( wp_json_encode( $sanitized ) . microtime() ) ),
		'form'       => html_entity_decode( get_the_title( (int) $form_id ) ),
		'name'       => (string) $name,
		'email'      => (string) $email,
		'phone'      => (string) $phone,
		'message'    => idc_connector_fields_text( $sanitized ),
		'listing'    => $listing,
		'page'       => (string) wp_get_referer(),
	] );
}, 20, 10 );

/** Formularios del tema en la ficha de la propiedad: contacto con el agente y agendar visita. */
function idc_connector_theme_form( string $kind ): void {
	$post = wp_unslash( $_POST );
	$listing = idc_connector_listing_from_url( esc_url_raw( (string) ( $post['ctpermalink'] ?? wp_get_referer() ) ) );
	idc_connector_send( [
		'event'   => $kind,
		'id'      => $kind . '-' . md5( wp_json_encode( $post ) . microtime() ),
		'name'    => sanitize_text_field( (string) ( $post['name'] ?? '' ) ),
		'email'   => sanitize_email( (string) ( $post['email'] ?? '' ) ),
		'phone'   => sanitize_text_field( (string) ( $post['phone'] ?? $post['ctphone'] ?? '' ) ),
		'message' => idc_connector_fields_text( array_diff_key( $post, array_flip( [ 'name', 'email', 'phone', 'ctphone', 'ctpermalink' ] ) ) ),
		'listing' => $listing,
	] );
}

add_action( 'ajax-submit-listings', static fn() => idc_connector_theme_form( 'inquiry' ), 20 );
add_action( 'ajax-submit-book-listing-showing', static fn() => idc_connector_theme_form( 'showing' ), 20 );

// Favoritos de Real Estate Core (meta wpfp_favorites): solo las propiedades agregadas.
function idc_connector_favorites_changed( int $user_id, $old, $new ): void {
	$added = array_diff( array_map( 'intval', is_array( $new ) ? $new : [] ), array_map( 'intval', is_array( $old ) ? $old : [] ) );
	$user = get_userdata( $user_id );
	if ( ! $user || ! $added ) {
		return;
	}
	foreach ( $added as $listing_id ) {
		$post = get_post( $listing_id );
		if ( $post && $post->post_type === 'listings' ) {
			idc_connector_send( [ 'event' => 'favorite', 'name' => $user->display_name, 'email' => $user->user_email, 'listing' => idc_connector_listing( $post ) ], false );
		}
	}
}

add_filter( 'update_user_metadata', static function ( $check, $user_id, $meta_key, $meta_value ) {
	if ( $meta_key === 'wpfp_favorites' ) {
		idc_connector_favorites_changed( (int) $user_id, get_user_meta( $user_id, 'wpfp_favorites', true ), $meta_value );
	}
	return $check;
}, 10, 4 );

add_action( 'added_user_meta', static function ( $meta_id, $user_id, $meta_key, $meta_value ) {
	if ( $meta_key === 'wpfp_favorites' ) {
		idc_connector_favorites_changed( (int) $user_id, [], $meta_value );
	}
}, 10, 4 );

// Busquedas guardadas con alertas por correo: se envian los criterios antes de que el modulo las guarde.
add_action( 'wp_ajax_ct_alert_creation_save', static function () {
	$user = wp_get_current_user();
	if ( ! $user || ! $user->ID ) {
		return;
	}
	$request = wp_unslash( $_REQUEST );
	$criteria = [];
	foreach ( [ 'ct_ct_status' => 'Estado', 'ct_ct_status_multi' => 'Estado', 'ct_property_type' => 'Tipo', 'beds' => 'Habitaciones', 'baths' => 'Banos', 'ct_city' => 'Ciudad', 'ct_state' => 'Departamento', 'ct_zipcode' => 'Codigo postal', 'ct_price_from' => 'Precio desde', 'ct_price_to' => 'Precio hasta', 'ct_keyword' => 'Palabras' ] as $key => $label ) {
		$value = is_scalar( $request[ $key ] ?? null ) ? sanitize_text_field( (string) $request[ $key ] ) : '';
		if ( $value !== '' ) {
			$criteria[] = $label . ': ' . $value;
		}
	}
	idc_connector_send( [ 'event' => 'saved_search', 'name' => $user->display_name, 'email' => $user->user_email, 'message' => implode( "\n", $criteria ) ], false );
}, 1 );

// Vistas de propiedades de usuarios con sesion (una por propiedad cada 12 horas).
add_action( 'template_redirect', static function () {
	if ( ! is_singular( 'listings' ) || ! is_user_logged_in() || idc_connector_settings()['send_views'] !== '1' ) {
		return;
	}
	$user = wp_get_current_user();
	$post = get_queried_object();
	$key = 'idc_view_' . md5( $user->ID . '-' . $post->ID );
	if ( get_transient( $key ) ) {
		return;
	}
	set_transient( $key, 1, 12 * HOUR_IN_SECONDS );
	idc_connector_send( [ 'event' => 'view', 'name' => $user->display_name, 'email' => $user->user_email, 'listing' => idc_connector_listing( $post ) ], false );
} );

/* ---------------------------------------------------------------------
 * reCAPTCHA v3 para CT Forms
 * ------------------------------------------------------------------- */

function idc_connector_recaptcha_on(): bool {
	$settings = idc_connector_settings();
	return $settings['recaptcha_enabled'] === '1' && $settings['recaptcha_site_key'] !== '' && $settings['recaptcha_secret'] !== '';
}

// Se verifica antes que CT Forms (prioridad 1): CT Forms solo valida el nonce, que es publico.
function idc_connector_check_recaptcha(): void {
	if ( ! idc_connector_recaptcha_on() || is_user_logged_in() ) {
		return;
	}
	$settings = idc_connector_settings();
	$token = sanitize_text_field( wp_unslash( (string) ( $_POST['idc_recaptcha'] ?? '' ) ) );
	if ( $token === '' ) {
		wp_send_json_error( [ 'message' => 'No pudimos verificar el formulario. Vuelve a intentarlo.' ] );
	}
	$response = wp_remote_post( 'https://www.google.com/recaptcha/api/siteverify', [
		'timeout' => 8,
		'body'    => [ 'secret' => $settings['recaptcha_secret'], 'response' => $token, 'remoteip' => sanitize_text_field( (string) ( $_SERVER['REMOTE_ADDR'] ?? '' ) ) ],
	] );
	$result = is_wp_error( $response ) ? [] : json_decode( (string) wp_remote_retrieve_body( $response ), true );
	if ( empty( $result['success'] ) || (float) ( $result['score'] ?? 0 ) < (float) $settings['recaptcha_score'] ) {
		wp_send_json_error( [ 'message' => 'No pudimos verificar el formulario. Vuelve a intentarlo.' ] );
	}
}

add_action( 'wp_ajax_ct_form_submit', 'idc_connector_check_recaptcha', 1 );
add_action( 'wp_ajax_nopriv_ct_form_submit', 'idc_connector_check_recaptcha', 1 );

// Agrega a cada formulario un token nuevo cada 90 segundos (cada token sirve una sola vez).
add_action( 'wp_footer', static function () {
	if ( ! idc_connector_recaptcha_on() || is_user_logged_in() ) {
		return;
	}
	$key = idc_connector_settings()['recaptcha_site_key'];
	?>
	<script src="https://www.google.com/recaptcha/api.js?render=<?php echo rawurlencode( $key ); ?>" async defer></script>
	<script>
	(function () {
		var key = <?php echo wp_json_encode( $key ); ?>;
		function refresh() {
			if (!window.grecaptcha || !grecaptcha.execute) { return setTimeout(refresh, 500); }
			grecaptcha.ready(function () {
				grecaptcha.execute(key, { action: 'ct_form' }).then(function (token) {
					document.querySelectorAll('form.ct-form, form[data-form-id]').forEach(function (form) {
						var input = form.querySelector('input[name="idc_recaptcha"]');
						if (!input) { input = document.createElement('input'); input.type = 'hidden'; input.name = 'idc_recaptcha'; form.appendChild(input); }
						input.value = token;
					});
				});
			});
		}
		refresh();
		setInterval(refresh, 90000);
		document.addEventListener('submit', function () { setTimeout(refresh, 1500); }, true);
	})();
	</script>
	<?php
}, 100 );

/* ---------------------------------------------------------------------
 * Exportacion de CT Leads Pro (para la importacion del CRM)
 * ------------------------------------------------------------------- */

/** Leads de CT Leads Pro por paginas: datos, etapa, agente, origen, etiquetas, notas, mensajes y propiedades. */
function idc_connector_leads_pro( array $data ): array {
	global $wpdb;
	$per_page = min( 200, max( 1, absint( $data['per_page'] ?? 200 ) ) );
	$page = max( 1, absint( $data['page'] ?? 1 ) );
	$users = get_users( [
		'meta_key'   => '_ct_lp_is_lead',
		'meta_value' => '1',
		'number'     => $per_page,
		'paged'      => $page,
		'orderby'    => 'ID',
		'order'      => 'ASC',
	] );
	if ( ! $users && $page === 1 && ! get_users( [ 'role' => 'lead', 'number' => 1 ] ) ) {
		return [ 'items' => [], 'error' => get_users( [ 'number' => 1 ] ) ? '' : 'No hay leads de CT Leads Pro en este sitio.' ];
	}
	$notes_table = $wpdb->prefix . 'ct_lp_notes';
	$messages_table = $wpdb->prefix . 'ct_lp_messages';
	$has_notes = $wpdb->get_var( $wpdb->prepare( 'SHOW TABLES LIKE %s', $notes_table ) ) === $notes_table;
	$has_messages = $wpdb->get_var( $wpdb->prepare( 'SHOW TABLES LIKE %s', $messages_table ) ) === $messages_table;
	$items = [];
	foreach ( $users as $user ) {
		$id = (int) $user->ID;
		$meta = static fn( string $key ) => is_scalar( $value = get_user_meta( $id, $key, true ) ) ? trim( (string) $value ) : '';
		$tags = get_user_meta( $id, '_ct_lp_tags', true );
		$agent = absint( $meta( '_ct_lp_assigned_agent' ) );
		$notes = [];
		if ( $has_notes ) {
			foreach ( (array) $wpdb->get_results( $wpdb->prepare( "SELECT author_id, content, created_at FROM {$notes_table} WHERE lead_id = %d ORDER BY id", $id ) ) as $note ) {
				$author = get_userdata( (int) $note->author_id );
				$notes[] = [ 'date' => (string) $note->created_at, 'author' => $author ? $author->display_name : '', 'text' => wp_strip_all_tags( (string) $note->content ) ];
			}
		}
		$messages = [];
		if ( $has_messages ) {
			foreach ( (array) $wpdb->get_results( $wpdb->prepare( "SELECT from_id, body, channel, created_at FROM {$messages_table} WHERE lead_id = %d ORDER BY id LIMIT 200", $id ) ) as $message ) {
				$from = (int) $message->from_id;
				$messages[] = [
					'date'   => (string) $message->created_at,
					'author' => $from === $id ? $user->display_name : ( ( $sender = get_userdata( $from ) ) ? $sender->display_name : '' ),
					'text'   => '[' . (string) $message->channel . '] ' . wp_strip_all_tags( (string) $message->body ),
				];
			}
		}
		$listings = [];
		foreach ( array_filter( array_map( 'absint', (array) get_user_meta( $id, '_ct_lp_interested_listing_ids', true ) ) ) as $listing_id ) {
			$post = get_post( $listing_id );
			if ( $post && $post->post_type === 'listings' ) {
				$listings[] = idc_connector_listing( $post );
			}
		}
		$items[] = [
			'id'       => $id,
			'name'     => $user->display_name,
			'email'    => str_ends_with( strtolower( $user->user_email ), '.invalid' ) ? '' : $user->user_email,
			'phone'    => $meta( '_ct_lp_phone' ),
			'stage'    => $meta( '_ct_lp_pipeline_stage' ),
			'agent'    => idc_connector_user( $agent ),
			'source'   => $meta( '_ct_lp_source' ),
			'tags'     => is_array( $tags ) ? array_values( array_map( 'strval', $tags ) ) : array_filter( array_map( 'trim', explode( ',', (string) $tags ) ) ),
			'created'  => (string) $user->user_registered,
			'notes'    => $notes,
			'messages' => $messages,
			'listings' => $listings,
		];
	}
	return [ 'items' => $items, 'page' => $page ];
}
