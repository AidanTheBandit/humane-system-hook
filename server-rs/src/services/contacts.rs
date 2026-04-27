use std::pin::Pin;

use tokio_stream::Stream;
use tonic::{Request, Response, Status};
use tracing::info;

use crate::proto::contacts::*;
use crate::proto::contacts::contacts_rpc_service_server::ContactsRpcService;

pub struct ContactsRpcServiceImpl;

#[tonic::async_trait]
impl ContactsRpcService for ContactsRpcServiceImpl {
    async fn get_contacts(
        &self,
        _request: Request<GetContactsRequest>,
    ) -> Result<Response<ContactList>, Status> {
        info!(">>> Contacts.GetContacts");
        Ok(Response::new(ContactList {
            contacts: vec![],
            encrypted_contacts: vec![],
            encrypted_contacts_versions: vec![],
        }))
    }

    type GetContactsPaginatedStreamingStream =
        Pin<Box<dyn Stream<Item = Result<GetContactsStreamingPageResponse, Status>> + Send>>;

    async fn get_contacts_paginated_streaming(
        &self,
        _request: Request<GetContactsStreamingPageRequest>,
    ) -> Result<Response<Self::GetContactsPaginatedStreamingStream>, Status> {
        info!(">>> Contacts.GetContactsPaginatedStreaming");

        let response = GetContactsStreamingPageResponse {
            page_content: vec![],
            page_num: 1,
            total_pages: 1,
        };

        let stream = tokio_stream::once(Ok(response));
        Ok(Response::new(Box::pin(stream)))
    }
}
